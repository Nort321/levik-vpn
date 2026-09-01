#!/usr/bin/env bash
set -euo pipefail

workspace_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_dir="${workspace_dir}/fork/wdtt-plus-v15/go_client"
output_dir="${workspace_dir}/build/android/jniLibs"
go_bin="${GO_BIN:-go}"
ndk_dir="${ANDROID_NDK_HOME:-}"

if grep -Rqs --include='*.go' '//go:linkname' "${source_dir}/third_party/anet"; then
  printf 'patched anet must not use private go:linkname; refusing global linker bypass\n' >&2
  exit 1
fi

if [[ "$(${go_bin} version)" != go\ version\ go1.26.5* ]]; then
  printf 'Go 1.26.5 is required (set GO_BIN)\n' >&2
  exit 1
fi
if [[ -z "${ndk_dir}" || ! -r "${ndk_dir}/source.properties" ]]; then
  printf 'ANDROID_NDK_HOME must point to Android NDK 29.0.14206865\n' >&2
  exit 1
fi
if ! grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*29\.0\.14206865$' "${ndk_dir}/source.properties"; then
  printf 'Android NDK 29.0.14206865 is required; automatic API/toolchain fallback is disabled\n' >&2
  exit 1
fi

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) host_tag="linux-x86_64" ;;
  Darwin-*) host_tag="darwin-x86_64" ;;
  *) printf 'unsupported NDK build host\n' >&2; exit 1 ;;
esac
toolchain="${ndk_dir}/toolchains/llvm/prebuilt/${host_tag}"
readelf_bin="${toolchain}/bin/llvm-readelf"

build_abi() {
  local abi="$1" goarch="$2" compiler="$3" interpreter="$4" goarm="${5:-}"
  local destination="${output_dir}/${abi}/liblevikrelay.so"
  test -x "${toolchain}/bin/${compiler}"
  mkdir -p "$(dirname "${destination}")"
  (
    cd "${source_dir}"
    env \
      GOOS=android \
      GOARCH="${goarch}" \
      GOARM="${goarm}" \
      GOTOOLCHAIN=local \
      GOFLAGS=-mod=readonly \
      CGO_ENABLED=1 \
      CC="${toolchain}/bin/${compiler}" \
      "${go_bin}" build \
        -buildvcs=false \
        -buildmode=pie \
        -trimpath \
        -ldflags='-s -w -buildid= -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' \
        -o "${destination}" \
        .
  )
  local elf_header program_headers go_metadata
  elf_header="$("${readelf_bin}" -h "${destination}")"
  program_headers="$("${readelf_bin}" -lW "${destination}")"
  go_metadata="$("${go_bin}" version -m "${destination}")"
  grep -Eq 'Type:[[:space:]]+DYN' <<<"${elf_header}"
  grep -Fq "Requesting program interpreter: ${interpreter}" <<<"${program_headers}"
  if ! awk '/ LOAD / { seen=1; if ($NF != "0x4000") bad=1 } END { exit (!seen || bad) }' <<<"${program_headers}"; then
    printf '%s does not have 16 KiB LOAD alignment\n' "${destination}" >&2
    exit 1
  fi
  grep -Fq 'go1.26.5' <<<"${go_metadata}"
  printf 'built %s with Android API 26 compiler\n' "${destination}"
}

build_abi arm64-v8a arm64 aarch64-linux-android26-clang /system/bin/linker64
build_abi armeabi-v7a arm armv7a-linux-androideabi26-clang /system/bin/linker 7
build_abi x86_64 amd64 x86_64-linux-android26-clang /system/bin/linker64
