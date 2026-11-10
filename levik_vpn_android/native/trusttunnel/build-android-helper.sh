#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
build_dir="${project_dir}/build"
output_dir="${build_dir}/android/jniLibs"
go_bin="${GO_BIN:-go}"
ndk_dir="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK 29.0.14206865}"
version="v1.14.4-lx.31"
archive="${build_dir}/sing-box-lx-${version}.tar.gz"
source_dir="${build_dir}/sing-box-lx-${version}"
checksum="c78eb77830b2fe84facd16bc867095fcbb1cae603f8ff15dd9e4909f15943c56"

case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) host="linux-x86_64" ;;
    Darwin-*) host="darwin-x86_64" ;;
    *) echo "Unsupported Android NDK host" >&2; exit 1 ;;
esac
toolchain="${ndk_dir}/toolchains/llvm/prebuilt/${host}/bin"
mkdir -p "${build_dir}"
if [[ ! -f "${archive}" ]]; then
    curl -fsSL --retry 3 -o "${archive}.partial" \
        "https://codeload.github.com/Leadaxe/sing-box-lx/tar.gz/refs/tags/${version}"
    mv "${archive}.partial" "${archive}"
fi
printf '%s  %s\n' "${checksum}" "${archive}" | shasum -a 256 --check --status
if [[ ! -f "${source_dir}/go.mod" ]]; then
    mkdir -p "${source_dir}"
    tar -xzf "${archive}" --strip-components 1 -C "${source_dir}"
fi

while read -r name repository revision digest; do
    module_archive="${build_dir}/${name}-${revision}.tar.gz"
    module_dir="${source_dir}/submodules/${name}"
    if [[ ! -f "${module_archive}" ]]; then
        curl -fsSL --retry 3 -o "${module_archive}.partial" \
            "https://codeload.github.com/Leadaxe/${repository}/tar.gz/${revision}"
        mv "${module_archive}.partial" "${module_archive}"
    fi
    printf '%s  %s\n' "${digest}" "${module_archive}" | shasum -a 256 --check --status
    if [[ ! -f "${module_dir}/go.mod" ]]; then
        mkdir -p "${module_dir}"
        tar -xzf "${module_archive}" --strip-components 1 -C "${module_dir}"
    fi
done <<'SOURCES'
utls utls-lx ad69b191e2f83367ec0ac5f3a1a61310732c43b9 5fb1ca008850537e691b2b22b777ad6519063d8bd518b75bba1b259a6fb74c76
sing-tun sing-tun-lx 94280f68f97bd4a296fa6a8648bcf312f304f69f ef67ab344ad3a9a070d0420f47d61b1f562b9f9a2dbaa05e688780f0d888a3ff
gvisor gvisor-lx 117243aa02fa2915cb53d1d4549bfca3a3ebf238 9a9256c7f27c47e28a46610450e09637a6674c8d50b35ba3b1627d6cfff82cc2
wireguard-go wireguard-go-awg2-lx 9115d9022cd0b2606ca1967d9b189d4c5936b543 2f31b55fb816adc2c50a7ed1f980ffff2cbea9eada546596752a11274932f266
SOURCES

helper_dir="${source_dir}/cmd/levik-trusttunnel"
mkdir -p "${helper_dir}"
cp "${project_dir}/source/levik-trusttunnel/main.go" "${helper_dir}/main.go"

build_abi() {
    local abi="$1" goarch="$2" compiler="$3" goarm="${4:-}"
    local output="${output_dir}/${abi}/libleviktrusttunnel.so"
    mkdir -p "$(dirname "${output}")"
    (
        cd "${source_dir}"
        env GOOS=android GOARCH="${goarch}" GOARM="${goarm}" CGO_ENABLED=1 \
            GOTOOLCHAIN=local GOFLAGS=-mod=readonly CC="${toolchain}/${compiler}" \
            "${go_bin}" build -buildvcs=false -buildmode=pie -trimpath \
            -tags with_quic,with_trusttunnel,with_utls \
            -ldflags '-s -w -buildid= -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' \
            -o "${output}.next" ./cmd/levik-trusttunnel
    )
    "${toolchain}/llvm-readelf" -lW "${output}.next" | awk \
        '/ LOAD / { seen=1; if ($NF != "0x4000") bad=1 } END { exit (!seen || bad) }'
    mv "${output}.next" "${output}"
}

build_abi arm64-v8a arm64 aarch64-linux-android26-clang
build_abi armeabi-v7a arm armv7a-linux-androideabi26-clang 7
build_abi x86_64 amd64 x86_64-linux-android26-clang
