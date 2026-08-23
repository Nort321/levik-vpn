#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

readonly LIBXRAY_VERSION="v26.7.28"
readonly ARCHIVE_NAME="libxray-android.zip"
readonly ARCHIVE_SIZE_BYTES="95743748"
readonly ARCHIVE_SHA256="28b7dc9d6cc8455fcca5cbd56e387003a7bfb558128651a64899dc3a8ccff666"
readonly AAR_SHA256="4708a361a74f7e955635dbe3661cefb459bdc867423c3b1826a2c5a6ea4ac77d"
readonly ARCHIVE_URL="https://github.com/XTLS/libXray/releases/download/${LIBXRAY_VERSION}/${ARCHIVE_NAME}"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
readonly TARGET_DIRECTORY="${REPOSITORY_ROOT}/levik_vpn_android/app/libs"
readonly TARGET_AAR="${TARGET_DIRECTORY}/libXray.aar"

for command_name in curl unzip wc; do
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

readonly TEMPORARY_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/levikvpn-libxray.XXXXXX")"
readonly ARCHIVE_PATH="${TEMPORARY_DIRECTORY}/${ARCHIVE_NAME}"
readonly EXTRACTED_DIRECTORY="${TEMPORARY_DIRECTORY}/extracted"
readonly EXTRACTED_AAR="${EXTRACTED_DIRECTORY}/libxray-android/libXray.aar"
readonly TEMPORARY_TARGET="${TARGET_DIRECTORY}/.libXray.aar.tmp.$$"

cleanup() {
  rm -rf -- "${TEMPORARY_DIRECTORY}"
  rm -f -- "${TEMPORARY_TARGET}"
}
trap cleanup EXIT

curl \
  --proto '=https' \
  --tlsv1.2 \
  --fail \
  --location \
  --silent \
  --show-error \
  --retry 3 \
  --retry-all-errors \
  --connect-timeout 15 \
  --max-time 600 \
  --output "${ARCHIVE_PATH}" \
  "${ARCHIVE_URL}"

archive_size="$(wc -c <"${ARCHIVE_PATH}")"
archive_size="${archive_size//[[:space:]]/}"
if [[ "${archive_size}" != "${ARCHIVE_SIZE_BYTES}" ]]; then
  printf 'ERROR: libXray archive size mismatch: expected %s, received %s.\n' \
    "${ARCHIVE_SIZE_BYTES}" "${archive_size}" >&2
  exit 1
fi

archive_digest="$(sha256_file "${ARCHIVE_PATH}")"
if [[ "${archive_digest}" != "${ARCHIVE_SHA256}" ]]; then
  printf 'ERROR: libXray archive SHA-256 mismatch.\n' >&2
  exit 1
fi

mkdir -p -- "${EXTRACTED_DIRECTORY}"
unzip -qq -- "${ARCHIVE_PATH}" "libxray-android/libXray.aar" -d "${EXTRACTED_DIRECTORY}"

if [[ ! -f "${EXTRACTED_AAR}" || -L "${EXTRACTED_AAR}" ]]; then
  printf 'ERROR: the verified archive does not contain the expected regular AAR.\n' >&2
  exit 1
fi

aar_digest="$(sha256_file "${EXTRACTED_AAR}")"
if [[ "${aar_digest}" != "${AAR_SHA256}" ]]; then
  printf 'ERROR: extracted libXray AAR SHA-256 mismatch.\n' >&2
  exit 1
fi

mkdir -p -- "${TARGET_DIRECTORY}"
install -m 0644 -- "${EXTRACTED_AAR}" "${TEMPORARY_TARGET}"
mv -f -- "${TEMPORARY_TARGET}" "${TARGET_AAR}"

printf 'Verified libXray %s (%s) at %s\n' \
  "${LIBXRAY_VERSION}" "${AAR_SHA256}" "${TARGET_AAR#"${REPOSITORY_ROOT}/"}"
