#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
go_bin="${DNSTT_GO_BIN:-${GO_BIN:-go}}"
ndk="${ANDROID_NDK_HOME:?Android NDK 29.0.14206865 is required}"
[[ "$("$go_bin" env GOVERSION)" == go1.26.9 ]]
grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*29\.0\.14206865$' "$ndk/source.properties"
case "$(uname -s)" in
 Darwin) host=darwin-x86_64 ;;
 Linux) host=linux-x86_64 ;;
 *) exit 1 ;;
esac
toolchain="$ndk/toolchains/llvm/prebuilt/$host"
build() {
 local abi="$1" arch="$2" cc="$3" arm="${4:-}"
 local out="$root/build/android/jniLibs/$abi/liblevikdnstt.so"
 mkdir -p "$(dirname "$out")"
 (cd "$root/fork" && env GOOS=android GOARCH="$arch" GOARM="$arm" GOTOOLCHAIN=local GOFLAGS=-mod=readonly \
  CGO_ENABLED=1 CC="$toolchain/bin/$cc" "$go_bin" build -buildvcs=false -buildmode=pie -trimpath \
  -ldflags='-s -w -buildid= -linkmode=external -extldflags=-Wl,-z,max-page-size=16384' -o "$out" ./dnstt-client)
 "$toolchain/bin/llvm-readelf" -lW "$out" | awk '/ LOAD / {seen=1;if($NF!="0x4000")bad=1} END{exit(!seen||bad)}'
}
build arm64-v8a arm64 aarch64-linux-android26-clang
build armeabi-v7a arm armv7a-linux-androideabi26-clang 7
build x86_64 amd64 x86_64-linux-android26-clang
