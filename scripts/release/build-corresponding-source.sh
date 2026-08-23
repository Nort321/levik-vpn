#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
readonly SOURCE_LOCK="${REPOSITORY_ROOT}/release/native-sources.lock.json"
readonly NATIVE_AAR="${REPOSITORY_ROOT}/levik_vpn_android/app/libs/libXray.aar"
readonly REQUIRED_GO_VERSION="go1.26.5"
readonly EXPECTED_AAR_SHA256="4708a361a74f7e955635dbe3661cefb459bdc867423c3b1826a2c5a6ea4ac77d"

usage() {
  printf 'Usage: %s <version> <output-directory>\n' "${0##*/}" >&2
  printf 'Example: %s 1.10.0 /tmp/levik-release\n' "${0##*/}" >&2
}

if [[ $# -ne 2 ]]; then
  usage
  exit 64
fi

readonly RELEASE_VERSION="$1"
readonly REQUESTED_OUTPUT_DIRECTORY="$2"

if [[ ! "${RELEASE_VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z]+)*$ ]]; then
  printf 'ERROR: invalid release version: %s\n' "${RELEASE_VERSION}" >&2
  exit 64
fi

for command_name in curl git go python3 tar unzip wc; do
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

if [[ ! -f "${SOURCE_LOCK}" || -L "${SOURCE_LOCK}" ]]; then
  printf 'ERROR: native source lock is missing or is not a regular file.\n' >&2
  exit 2
fi
if [[ ! -f "${NATIVE_AAR}" || -L "${NATIVE_AAR}" ]]; then
  printf 'ERROR: verified libXray.aar must be installed before source bundling.\n' >&2
  exit 2
fi
if [[ "$(sha256_file "${NATIVE_AAR}")" != "${EXPECTED_AAR_SHA256}" ]]; then
  printf 'ERROR: libXray.aar does not match the pinned native artifact.\n' >&2
  exit 1
fi

if ! git -C "${REPOSITORY_ROOT}" diff --quiet --no-ext-diff -- ||
  ! git -C "${REPOSITORY_ROOT}" diff --cached --quiet --no-ext-diff --; then
  printf 'ERROR: corresponding source must be generated from a clean Git tree.\n' >&2
  exit 1
fi

mkdir -p -- "${REQUESTED_OUTPUT_DIRECTORY}"
readonly OUTPUT_DIRECTORY="$(cd "${REQUESTED_OUTPUT_DIRECTORY}" && pwd)"
readonly OUTPUT_NAME="LevikVPN-corresponding-source-${RELEASE_VERSION}.tar.gz"
readonly FINAL_ARCHIVE="${OUTPUT_DIRECTORY}/${OUTPUT_NAME}"
readonly FINAL_CHECKSUM="${FINAL_ARCHIVE}.sha256"
if [[ -e "${FINAL_ARCHIVE}" || -L "${FINAL_ARCHIVE}" || -e "${FINAL_CHECKSUM}" ]]; then
  printf 'ERROR: refusing to overwrite existing release output: %s\n' "${FINAL_ARCHIVE}" >&2
  exit 1
fi

readonly TEMPORARY_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/levikvpn-source.XXXXXX")"
readonly DOWNLOAD_DIRECTORY="${TEMPORARY_DIRECTORY}/downloads"
readonly BUNDLE_ROOT="${TEMPORARY_DIRECTORY}/LevikVPN-corresponding-source-${RELEASE_VERSION}"
readonly APPLICATION_SOURCE="${BUNDLE_ROOT}/application"
readonly LIBXRAY_SOURCE="${BUNDLE_ROOT}/native/libXray"
readonly UPSTREAM_ARCHIVES="${BUNDLE_ROOT}/native/upstream-archives"
readonly MODULE_CACHE="${BUNDLE_ROOT}/native/go-module-cache"
readonly EVIDENCE_DIRECTORY="${BUNDLE_ROOT}/evidence"
readonly TEMPORARY_ARCHIVE="${TEMPORARY_DIRECTORY}/${OUTPUT_NAME}"

cleanup() {
  # Go deliberately makes module-cache files read-only. Restore owner write
  # permission so cleanup cannot turn an otherwise valid release into a failure.
  chmod -R u+w -- "${TEMPORARY_DIRECTORY}" 2>/dev/null || true
  rm -rf -- "${TEMPORARY_DIRECTORY}" || true
}
trap cleanup EXIT

mkdir -p -- \
  "${DOWNLOAD_DIRECTORY}" \
  "${APPLICATION_SOURCE}" \
  "${LIBXRAY_SOURCE}" \
  "${UPSTREAM_ARCHIVES}" \
  "${EVIDENCE_DIRECTORY}"

readonly SOURCE_COMMIT="$(git -C "${REPOSITORY_ROOT}" rev-parse --verify HEAD)"
readonly SOURCE_COMMIT_EPOCH="$(git -C "${REPOSITORY_ROOT}" show -s --format=%ct HEAD)"

git -C "${REPOSITORY_ROOT}" archive --format=tar "${SOURCE_COMMIT}" |
  tar -xf - -C "${APPLICATION_SOURCE}"

libxray_archive=""
while IFS=$'\t' read -r archive_index archive_name archive_url expected_size expected_sha; do
  if [[ ! "${archive_index}" =~ ^[0-9]+$ ||
        ! "${archive_name}" =~ ^[a-z0-9][a-z0-9._-]*\.tar\.gz$ ||
        ! "${archive_url}" =~ ^https://(codeload\.github\.com|go\.dev)/ ||
        ! "${expected_size}" =~ ^[0-9]+$ ||
        ! "${expected_sha}" =~ ^[0-9a-f]{64}$ ]]; then
    printf 'ERROR: malformed source archive lock entry.\n' >&2
    exit 1
  fi

  archive_path="${DOWNLOAD_DIRECTORY}/${archive_name}"
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
    --output "${archive_path}" \
    "${archive_url}"

  actual_size="$(wc -c <"${archive_path}")"
  actual_size="${actual_size//[[:space:]]/}"
  if [[ "${actual_size}" != "${expected_size}" ]]; then
    printf 'ERROR: source archive size mismatch for %s.\n' "${archive_name}" >&2
    exit 1
  fi
  if [[ "$(sha256_file "${archive_path}")" != "${expected_sha}" ]]; then
    printf 'ERROR: source archive SHA-256 mismatch for %s.\n' "${archive_name}" >&2
    exit 1
  fi

  cp -- "${archive_path}" "${UPSTREAM_ARCHIVES}/${archive_name}"
  if [[ "${archive_index}" == "0" ]]; then
    libxray_archive="${archive_path}"
  fi
done < <(
  python3 - "${SOURCE_LOCK}" <<'PY'
import json
import re
import sys

with open(sys.argv[1], "r", encoding="utf-8") as source:
    payload = json.load(source)

if payload.get("schemaVersion") != 1:
    raise SystemExit("unsupported native source lock schema")

for index, archive in enumerate(payload.get("sourceArchives", [])):
    base = re.sub(r"[^a-z0-9]+", "-", archive["name"].lower()).strip("-")
    version = re.sub(r"[^a-z0-9.]+", "-", archive["version"].lower()).strip("-")
    filename = f"{index:02d}-{base}-{version}.tar.gz"
    fields = (index, filename, archive["url"], archive["size"], archive["sha256"])
    if any("\t" in str(value) or "\n" in str(value) for value in fields):
        raise SystemExit("invalid native source lock field")
    print("\t".join(str(value) for value in fields))
PY
)

if [[ -z "${libxray_archive}" ]]; then
  printf 'ERROR: libXray source archive was not selected.\n' >&2
  exit 1
fi

tar -xzf "${libxray_archive}" --strip-components=1 -C "${LIBXRAY_SOURCE}"
cp -- "${SOURCE_LOCK}" "${EVIDENCE_DIRECTORY}/native-sources.lock.json"
cp -- "${REPOSITORY_ROOT}/THIRD_PARTY_NOTICES.md" "${EVIDENCE_DIRECTORY}/THIRD_PARTY_NOTICES.md"
cp -- \
  "${REPOSITORY_ROOT}/levik_vpn_android/THIRD_PARTY_NOTICES.md" \
  "${EVIDENCE_DIRECTORY}/ANDROID_THIRD_PARTY_NOTICES.md"

actual_go_version="$(go version | awk '{print $3}')"
if [[ "${actual_go_version}" != "${REQUIRED_GO_VERSION}" ]]; then
  printf 'ERROR: expected %s, received %s.\n' "${REQUIRED_GO_VERSION}" "${actual_go_version}" >&2
  exit 1
fi

readonly PRIVATE_GO_MODULE_CACHE="${TEMPORARY_DIRECTORY}/gomodcache"
readonly PRIVATE_GO_BUILD_CACHE="${TEMPORARY_DIRECTORY}/gocache"
mkdir -p -- "${PRIVATE_GO_MODULE_CACHE}" "${PRIVATE_GO_BUILD_CACHE}"
export GOMODCACHE="${PRIVATE_GO_MODULE_CACHE}"
export GOCACHE="${PRIVATE_GO_BUILD_CACHE}"
export GOTOOLCHAIN=local

(
  cd "${LIBXRAY_SOURCE}"
  go mod download all
  go mod verify
  go list -m -json all >"${EVIDENCE_DIRECTORY}/go-module-inventory.json"
  go mod vendor
)
cp -R -- "${PRIVATE_GO_MODULE_CACHE}" "${MODULE_CACHE}"

unzip -p "${NATIVE_AAR}" jni/arm64-v8a/libgojni.so \
  >"${TEMPORARY_DIRECTORY}/libgojni.so"
go version -m "${TEMPORARY_DIRECTORY}/libgojni.so" \
  >"${EVIDENCE_DIRECTORY}/libgojni-build-info.txt"

for required_build_entry in \
  "${REQUIRED_GO_VERSION}" \
  $'dep\tgithub.com/xtls/xray-core\tv1.260327.1-0.20260728075948-5ca6f4b7d4dc' \
  $'dep\tgithub.com/sagernet/sing\tv0.5.1' \
  $'dep\tgithub.com/sagernet/sing-shadowsocks\tv0.2.7'; do
  if ! grep -F -- "${required_build_entry}" "${EVIDENCE_DIRECTORY}/libgojni-build-info.txt" >/dev/null; then
    printf 'ERROR: native build metadata does not contain %s.\n' "${required_build_entry}" >&2
    exit 1
  fi
done

python3 - \
  "${EVIDENCE_DIRECTORY}/SOURCE-MANIFEST.json" \
  "${SOURCE_LOCK}" \
  "${SOURCE_COMMIT}" \
  "${SOURCE_COMMIT_EPOCH}" \
  "${RELEASE_VERSION}" <<'PY'
import json
import sys
from datetime import datetime, timezone

output_path, lock_path, commit, epoch, version = sys.argv[1:]
with open(lock_path, "r", encoding="utf-8") as source:
    lock = json.load(source)

manifest = {
    "schemaVersion": 1,
    "releaseVersion": version,
    "applicationCommit": commit,
    "sourceDateEpoch": int(epoch),
    "generatedAt": datetime.fromtimestamp(int(epoch), timezone.utc).isoformat().replace("+00:00", "Z"),
    "nativeArtifact": lock["nativeArtifact"],
    "sourceArchives": lock["sourceArchives"],
    "contents": {
        "application": "Git archive of the exact Android repository commit",
        "native/libXray": "Pinned libXray source, build scripts, and vendored Go dependencies",
        "native/go-module-cache": "Complete module downloads for the pinned Go module graph",
        "native/upstream-archives": "Digest-locked source archives for audited copyleft inputs and Go",
        "evidence": "Module inventory, native build metadata, notices, and source locks",
    },
}
with open(output_path, "w", encoding="utf-8", newline="\n") as output:
    json.dump(manifest, output, ensure_ascii=False, indent=2, sort_keys=True)
    output.write("\n")
PY

python3 - "${BUNDLE_ROOT}" "${TEMPORARY_ARCHIVE}" <<'PY'
import gzip
import os
import pathlib
import sys
import tarfile

root = pathlib.Path(sys.argv[1])
output_path = pathlib.Path(sys.argv[2])
parent = root.parent

def normalized(info: tarfile.TarInfo) -> tarfile.TarInfo:
    info.uid = 0
    info.gid = 0
    info.uname = "root"
    info.gname = "root"
    info.mtime = 0
    return info

paths = [root]
paths.extend(sorted(root.rglob("*"), key=lambda item: item.as_posix()))
with output_path.open("wb") as raw:
    with gzip.GzipFile(filename="", mode="wb", compresslevel=9, fileobj=raw, mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as archive:
            for path in paths:
                relative = path.relative_to(parent).as_posix()
                info = normalized(archive.gettarinfo(str(path), arcname=relative))
                if info.issym() or info.islnk():
                    target = pathlib.PurePosixPath(info.linkname)
                    if target.is_absolute() or ".." in target.parts:
                        raise SystemExit(f"unsafe symlink in source bundle: {relative}")
                if info.isfile():
                    with path.open("rb") as source:
                        archive.addfile(info, source)
                else:
                    archive.addfile(info)
PY

readonly ARCHIVE_SHA256="$(sha256_file "${TEMPORARY_ARCHIVE}")"
mv -- "${TEMPORARY_ARCHIVE}" "${FINAL_ARCHIVE}"
printf '%s  %s\n' "${ARCHIVE_SHA256}" "${OUTPUT_NAME}" >"${FINAL_CHECKSUM}"

printf 'Corresponding source bundle: %s\n' "${FINAL_ARCHIVE}"
printf 'SHA-256: %s\n' "${ARCHIVE_SHA256}"
