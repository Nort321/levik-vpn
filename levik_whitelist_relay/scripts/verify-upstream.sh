#!/usr/bin/env bash
set -euo pipefail

workspace_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lock_file="${workspace_dir}/source/upstream.lock.json"
tmp_dir="$(mktemp -d)"
trap 'rm -rf -- "${tmp_dir}"' EXIT

if command -v sha256sum >/dev/null 2>&1; then
  sha256() { sha256sum "$1" | awk '{print $1}'; }
else
  sha256() { shasum -a 256 "$1" | awk '{print $1}'; }
fi

verify_archive() {
  local name="$1" url="$2" expected="$3" output="$4"
  curl --fail --location --silent --show-error --proto '=https' --tlsv1.2 "${url}" --output "${output}"
  local actual
  actual="$(sha256 "${output}")"
  if [[ "${actual}" != "${expected}" ]]; then
    printf 'checksum mismatch for %s: got %s\n' "${name}" "${actual}" >&2
    exit 1
  fi
  printf 'verified %s %s\n' "${name}" "${actual}"
}

test -r "${lock_file}"
verify_archive \
  "WDTT-Plus-v15" \
  "https://codeload.github.com/Ivan4537/WDTT-Plus/tar.gz/3038b8ddc0306feb21d3c3624e2bc1c3c14639ad" \
  "07c6a4c200c87c636a6d0855385e96284e73ddcc5b80c912a463b068ef964223" \
  "${tmp_dir}/wdtt-plus.tar.gz"
verify_archive \
  "qWDTT-provenance" \
  "https://codeload.github.com/SpaceNeuroX/proxy-turn-vk-android/tar.gz/fae121efc3ef57b633516601d3c0d6b1be1fde7c" \
  "1a2b4f559890e0688ea608c6890a7794131acd583acc612d23e30f59e8c53e9c" \
  "${tmp_dir}/qwdtt.tar.gz"

