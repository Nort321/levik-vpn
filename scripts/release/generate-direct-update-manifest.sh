#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

usage() {
  printf 'Usage: %s <apk> <version-code> <version-name> <release-tag> <cert-sha256> <ru-changelog> <en-changelog> <update-private-key-pem> <public-key-spki-base64> <output-directory>\n' "${0##*/}" >&2
}

if [[ $# -ne 10 ]]; then
  usage
  exit 64
fi

readonly APK_PATH="$1"
readonly EXPECTED_VERSION_CODE="$2"
readonly EXPECTED_VERSION_NAME="$3"
readonly RELEASE_TAG="$4"
readonly EXPECTED_CERTIFICATE_SHA256="$(printf '%s' "$5" | tr '[:upper:]' '[:lower:]')"
readonly CHANGELOG_RU_PATH="$6"
readonly CHANGELOG_EN_PATH="$7"
readonly UPDATE_PRIVATE_KEY_PATH="$8"
readonly UPDATE_PUBLIC_KEY_SPKI_BASE64="$9"
readonly REQUESTED_OUTPUT_DIRECTORY="${10}"
readonly EXPECTED_PACKAGE_NAME="com.leviknet.vpn"
readonly MAX_APK_SIZE_BYTES=$((512 * 1024 * 1024))

for command_name in apkanalyzer apksigner awk grep openssl python3 tr wc; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    printf 'ERROR: required command is unavailable: %s\n' "${command_name}" >&2
    exit 2
  fi
done

sha256_file() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -- "${path}" | awk '{print $1}'
    return
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 -- "${path}" | awk '{print $1}'
    return
  fi
  printf 'ERROR: sha256sum or shasum is required.\n' >&2
  exit 2
}

for regular_file in \
  "${APK_PATH}" \
  "${CHANGELOG_RU_PATH}" \
  "${CHANGELOG_EN_PATH}" \
  "${UPDATE_PRIVATE_KEY_PATH}"; do
  if [[ ! -f "${regular_file}" || -L "${regular_file}" ]]; then
    printf 'ERROR: required input is missing or is not a regular file: %s\n' "${regular_file}" >&2
    exit 2
  fi
done

if [[ ! "${EXPECTED_VERSION_CODE}" =~ ^[1-9][0-9]*$ ||
      ! "${EXPECTED_VERSION_NAME}" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z]+)*$ ||
      "${RELEASE_TAG}" != "v${EXPECTED_VERSION_NAME}" ||
      ! "${EXPECTED_CERTIFICATE_SHA256}" =~ ^[0-9a-f]{64}$ ]]; then
  printf 'ERROR: invalid version, release tag, or certificate input.\n' >&2
  exit 64
fi

python3 - "${CHANGELOG_RU_PATH}" "${CHANGELOG_EN_PATH}" <<'PY'
import pathlib
import sys

for raw_path in sys.argv[1:]:
    path = pathlib.Path(raw_path)
    payload = path.read_bytes()
    if not payload or len(payload) > 32768 or b"\x00" in payload:
        raise SystemExit(f"invalid changelog input: {path}")
    payload.decode("utf-8")
PY

readonly APK_SIZE="$(wc -c <"${APK_PATH}" | tr -d '[:space:]')"
if [[ ! "${APK_SIZE}" =~ ^[0-9]+$ || "${APK_SIZE}" -lt 1048576 || "${APK_SIZE}" -gt "${MAX_APK_SIZE_BYTES}" ]]; then
  printf 'ERROR: Direct APK size is outside the allowed release bounds.\n' >&2
  exit 1
fi

readonly ACTUAL_PACKAGE_NAME="$(apkanalyzer manifest application-id "${APK_PATH}")"
readonly ACTUAL_VERSION_CODE="$(apkanalyzer manifest version-code "${APK_PATH}")"
readonly ACTUAL_VERSION_NAME="$(apkanalyzer manifest version-name "${APK_PATH}")"
if [[ "${ACTUAL_PACKAGE_NAME}" != "${EXPECTED_PACKAGE_NAME}" ||
      "${ACTUAL_VERSION_CODE}" != "${EXPECTED_VERSION_CODE}" ||
      "${ACTUAL_VERSION_NAME}" != "${EXPECTED_VERSION_NAME}" ]]; then
  printf 'ERROR: APK package or version metadata does not match release inputs.\n' >&2
  exit 1
fi

apksigner verify --verbose --print-certs "${APK_PATH}" >/dev/null
readonly ACTUAL_CERTIFICATE_SHA256="$({
  apksigner verify --print-certs "${APK_PATH}"
} | awk -F ': ' '/certificate SHA-256 digest/ { print tolower($2); exit }')"
if [[ "${ACTUAL_CERTIFICATE_SHA256}" != "${EXPECTED_CERTIFICATE_SHA256}" ]]; then
  printf 'ERROR: APK signing certificate SHA-256 does not match the protected release value.\n' >&2
  exit 1
fi

mkdir -p -- "${REQUESTED_OUTPUT_DIRECTORY}"
readonly OUTPUT_DIRECTORY="$(cd "${REQUESTED_OUTPUT_DIRECTORY}" && pwd)"
readonly UPDATE_MANIFEST="${OUTPUT_DIRECTORY}/update.json"
readonly UPDATE_SIGNATURE="${OUTPUT_DIRECTORY}/update.json.sig"
readonly CHECKSUMS="${OUTPUT_DIRECTORY}/SHA256SUMS"
readonly APK_ASSET_NAME="LevikVPN-direct-${EXPECTED_VERSION_NAME}.apk"
readonly APK_ASSET_PATH="${OUTPUT_DIRECTORY}/${APK_ASSET_NAME}"
for output_path in "${APK_ASSET_PATH}" "${UPDATE_MANIFEST}" "${UPDATE_SIGNATURE}" "${CHECKSUMS}"; do
  if [[ -e "${output_path}" || -L "${output_path}" ]]; then
    printf 'ERROR: refusing to overwrite release output: %s\n' "${output_path}" >&2
    exit 1
  fi
done

readonly TEMPORARY_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/levikvpn-update.XXXXXX")"
cleanup() {
  rm -rf -- "${TEMPORARY_DIRECTORY}"
}
trap cleanup EXIT

readonly PRIVATE_DERIVED_PUBLIC_KEY="${TEMPORARY_DIRECTORY}/private-public.der"
readonly EXPECTED_PUBLIC_KEY_DER="${TEMPORARY_DIRECTORY}/expected-public.der"
readonly EXPECTED_PUBLIC_KEY_PEM="${TEMPORARY_DIRECTORY}/expected-public.pem"
readonly BINARY_SIGNATURE="${TEMPORARY_DIRECTORY}/update.json.sig.der"

openssl pkey -in "${UPDATE_PRIVATE_KEY_PATH}" -pubout -outform DER \
  -out "${PRIVATE_DERIVED_PUBLIC_KEY}"
if ! openssl ec -pubin -inform DER -in "${PRIVATE_DERIVED_PUBLIC_KEY}" -text -noout \
  2>/dev/null | grep -F 'ASN1 OID: prime256v1' >/dev/null; then
  printf 'ERROR: update manifest signing requires an ECDSA P-256 key.\n' >&2
  exit 1
fi
python3 - \
  "${UPDATE_PUBLIC_KEY_SPKI_BASE64}" \
  "${EXPECTED_PUBLIC_KEY_DER}" \
  "${PRIVATE_DERIVED_PUBLIC_KEY}" <<'PY'
import base64
import hmac
import pathlib
import sys

encoded, expected_path, derived_path = sys.argv[1:]
try:
    expected = base64.b64decode(encoded, validate=True)
except ValueError as error:
    raise SystemExit(f"invalid update public key encoding: {error}")
derived = pathlib.Path(derived_path).read_bytes()
if not hmac.compare_digest(expected, derived):
    raise SystemExit("update private key does not match the embedded public key")
pathlib.Path(expected_path).write_bytes(expected)
PY
openssl pkey -pubin -inform DER -in "${EXPECTED_PUBLIC_KEY_DER}" \
  -out "${EXPECTED_PUBLIC_KEY_PEM}"

readonly APK_SHA256="$(sha256_file "${APK_PATH}")"
readonly APK_URL="https://leviknet.com/downloads/android/stable/${RELEASE_TAG}/${APK_ASSET_NAME}"
cp -- "${APK_PATH}" "${APK_ASSET_PATH}"

python3 - \
  "${UPDATE_MANIFEST}" \
  "${EXPECTED_VERSION_CODE}" \
  "${EXPECTED_VERSION_NAME}" \
  "${APK_URL}" \
  "${APK_SIZE}" \
  "${APK_SHA256}" \
  "${EXPECTED_CERTIFICATE_SHA256}" \
  "${CHANGELOG_RU_PATH}" \
  "${CHANGELOG_EN_PATH}" <<'PY'
import json
import pathlib
import sys

(
    output_path,
    version_code,
    version_name,
    apk_url,
    apk_size,
    apk_sha256,
    certificate_sha256,
    changelog_ru_path,
    changelog_en_path,
) = sys.argv[1:]

payload = {
    "schemaVersion": 1,
    "packageName": "com.leviknet.vpn",
    "versionCode": int(version_code),
    "versionName": version_name,
    "apkUrl": apk_url,
    "apkSize": int(apk_size),
    "apkSha256": apk_sha256,
    "signingCertificateSha256": certificate_sha256,
    "forceUpdate": False,
    "changelogRu": pathlib.Path(changelog_ru_path).read_text(encoding="utf-8").strip(),
    "changelogEn": pathlib.Path(changelog_en_path).read_text(encoding="utf-8").strip(),
}
with open(output_path, "w", encoding="utf-8", newline="\n") as output:
    json.dump(payload, output, ensure_ascii=False, indent=2)
    output.write("\n")
PY

openssl dgst -sha256 -sign "${UPDATE_PRIVATE_KEY_PATH}" \
  -out "${BINARY_SIGNATURE}" "${UPDATE_MANIFEST}"
openssl dgst -sha256 -verify "${EXPECTED_PUBLIC_KEY_PEM}" \
  -signature "${BINARY_SIGNATURE}" "${UPDATE_MANIFEST}" >/dev/null
python3 - "${BINARY_SIGNATURE}" "${UPDATE_SIGNATURE}" <<'PY'
import base64
import pathlib
import sys

signature = pathlib.Path(sys.argv[1]).read_bytes()
pathlib.Path(sys.argv[2]).write_text(
    base64.b64encode(signature).decode("ascii") + "\n",
    encoding="ascii",
    newline="\n",
)
PY

printf '%s  %s\n' "${APK_SHA256}" "${APK_ASSET_NAME}" >"${CHECKSUMS}"
printf '%s  %s\n' "$(sha256_file "${UPDATE_MANIFEST}")" "update.json" >>"${CHECKSUMS}"
printf '%s  %s\n' "$(sha256_file "${UPDATE_SIGNATURE}")" "update.json.sig" >>"${CHECKSUMS}"

printf 'Generated signed Direct update manifest for %s (%s).\n' \
  "${EXPECTED_VERSION_NAME}" "${EXPECTED_VERSION_CODE}"
