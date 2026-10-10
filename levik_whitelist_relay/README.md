# Levik WhiteList Relay — Android client

This directory holds the open client half of the Levik WhiteList relay: the
sources of `liblevikrelay.so`, which the Direct Android build packages and
runs as an isolated helper process.

- `fork/wdtt-plus-v15/go_client`: GPL-3.0-only fork of the WDTT Plus v15 Go
  client with Levik WRAP v2, file-only bootstrap secrets and Android control
  channels.
- `source`: immutable upstream and toolchain locks.
- `scripts/verify-upstream.sh`: checks the pinned upstream archive.
- `scripts/build-android-client.sh`: reproducible Android build.

The relay server, node agent and deployment configuration are not part of
this repository.

## Android build

Output is exactly `build/android/jniLibs/<abi>/liblevikrelay.so` for
`arm64-v8a`, `armeabi-v7a` and `x86_64`. The script requires NDK
`29.0.14206865` and Go 1.26.5, invokes only the API-26 compilers and emits
only PIE (`ET_DYN`) executables with the Android linker and 16 KiB LOAD
alignment. It fails instead of silently switching toolchains.

The app extracts the ELF and executes it with only
`-levik-control-sock=@levik_wlr_<random>` in argv. Runtime secrets are passed
through the control socket, never argv or environment variables.

## Client boundaries

- Levik mode disables MASQUE and system-DNS fallback.
- Every enabled external TURN, VK-auth, captcha and direct-DNS socket must
  receive Android `protect()` plus cellular `bindSocket()` acknowledgement
  before connect.
