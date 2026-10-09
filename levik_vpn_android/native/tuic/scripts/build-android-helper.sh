#!/usr/bin/env bash
# Builds a minimal sing-box (TUIC v5 client + SOCKS5 inbound) for the Direct
# Android distribution. Xray has no TUIC client; the app runs this binary as a
# loopback SOCKS5 sidecar and protects its sockets through `protect_path`.
set -euo pipefail

workspace_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${OUTPUT_DIR:-${workspace_dir}/build/android/jniLibs}"
work_dir="${workspace_dir}/build/source"
go_bin="${TUIC_GO_BIN:-${GO_BIN:-go}}"
ndk_dir="${ANDROID_NDK_HOME:-}"

readonly version="1.14.2"
readonly source_url="https://github.com/SagerNet/sing-box/archive/refs/tags/v${version}.tar.gz"
readonly source_sha256="67dd8f8c37ecaaadcfcafad1f0827eed4b034c963b86fd3aa5c0d7a36876845d"
# Only TUIC (QUIC) is compiled in; linkname tags match the upstream release build.
readonly build_tags="with_quic,badlinkname,tfogo_checklinkname0"

if [[ "$("${go_bin}" version)" != go\ version\ go1.26.8* ]]; then
  printf 'Go 1.26.8 is required (set TUIC_GO_BIN or GO_BIN)\n' >&2
  exit 1
fi
if [[ -z "${ndk_dir}" || ! -r "${ndk_dir}/source.properties" ]] ||
  ! grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*29\.0\.14206865$' "${ndk_dir}/source.properties"; then
  printf 'ANDROID_NDK_HOME must point to Android NDK 29.0.14206865\n' >&2
  exit 1
fi
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) host_tag="linux-x86_64" ;;
  Darwin-*) host_tag="darwin-x86_64" ;;
  *) printf 'unsupported NDK build host\n' >&2; exit 1 ;;
esac
toolchain="${ndk_dir}/toolchains/llvm/prebuilt/${host_tag}"
readelf_bin="${toolchain}/bin/llvm-readelf"

mkdir -p "${work_dir}"
archive="${work_dir}/sing-box-${version}.tar.gz"
source_dir="${work_dir}/sing-box-${version}"
if [[ ! -f "${archive}" ]]; then
  curl -fsSL --retry 3 -o "${archive}.partial" "${source_url}"
  mv "${archive}.partial" "${archive}"
fi
if [[ "$(shasum -a 256 "${archive}" | cut -d' ' -f1)" != "${source_sha256}" ]]; then
  printf 'sing-box source archive SHA-256 mismatch\n' >&2
  exit 1
fi
if [[ ! -d "${source_dir}" ]]; then
  tar -xzf "${archive}" -C "${work_dir}"
fi

build_abi() {
  local abi="$1" goarch="$2" compiler="$3" interpreter="$4" goarm="${5:-}"
  local destination="${output_dir}/${abi}/libleviktuic.so"
  test -x "${toolchain}/bin/${compiler}"
  mkdir -p "$(dirname "${destination}")"
  (
    cd "${source_dir}"
    # Dependencies are verified against the pinned go.sum of the release.
    env GOOS=android GOARCH="${goarch}" GOARM="${goarm}" GOTOOLCHAIN=local \
      GOFLAGS=-mod=readonly CGO_ENABLED=1 CC="${toolchain}/bin/${compiler}" \
      "${go_bin}" build -buildvcs=false -buildmode=pie -trimpath -tags "${build_tags}" \
      -ldflags="-X 'github.com/sagernet/sing-box/constant.Version=${version}' -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid= -linkmode=external -extldflags=-Wl,-z,max-page-size=16384" \
      -o "${destination}" ./cmd/sing-box
  )
  local elf_header program_headers go_metadata
  elf_header="$("${readelf_bin}" -h "${destination}")"
  program_headers="$("${readelf_bin}" -lW "${destination}")"
  go_metadata="$("${go_bin}" version -m "${destination}")"
  grep -Eq 'Type:[[:space:]]+DYN' <<<"${elf_header}"
  grep -Fq "Requesting program interpreter: ${interpreter}" <<<"${program_headers}"
  if ! awk '/ LOAD / { seen=1; if ($NF != "0x4000") bad=1 } END { exit (!seen || bad) }' <<<"${program_headers}"; then
    printf 'TUIC helper does not have 16 KiB LOAD alignment\n' >&2
    exit 1
  fi
  grep -Fq 'go1.26.8' <<<"${go_metadata}"
  grep -Fq "${build_tags}" <<<"${go_metadata}"
  printf 'built %s with Android API 26 compiler\n' "${destination}"
}

build_abi arm64-v8a arm64 aarch64-linux-android26-clang /system/bin/linker64
build_abi armeabi-v7a arm armv7a-linux-androideabi26-clang /system/bin/linker 7
build_abi x86_64 amd64 x86_64-linux-android26-clang /system/bin/linker64
