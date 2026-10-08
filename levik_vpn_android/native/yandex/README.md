# Yandex helper for Direct Android

This directory contains the Android SOCKS helper and its local package
dependencies from the modified OpenFlux revision recorded in
`source/upstream.json`. It contains no node agent, deployment configuration,
control plane, backend, or standalone server command.

The runtime source is the same implementation validated on the pilot device.
Only the packages needed to build `cmd/levik-yandex-android` are included.
Tests for unrelated upstream providers are omitted.

Build with `scripts/build-android-helper.sh`, Go 1.26.8 and Android NDK
29.0.14206865. Set `YANDEX_GO_BIN` and `ANDROID_NDK_HOME` explicitly. Gradle
builds and validates all three Direct ABIs; Play excludes the helper.

OpenFlux is licensed under GPL-3.0-or-later; see `fork/openflux/LICENSE`.
Release evidence includes the exact sources, dependency graph and per-ABI
build metadata.
