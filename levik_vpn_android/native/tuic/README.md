# TUIC helper for Direct Android

Xray (libXray) has no TUIC client. The Direct distribution runs a minimal
sing-box build as a loopback SOCKS5 sidecar: Xray keeps the TUN, routing and
DNS, and forwards the selected TUIC server's traffic to the sidecar.

- Source: sing-box `v1.14.2`, pinned by archive SHA-256 in
  `scripts/build-android-helper.sh` (see `source/upstream.json`).
- Build tags: `with_quic` only (plus the upstream linkname tags).
- Socket protection: the sidecar sends every outbound socket to the app over
  the `protect_path` abstract Unix socket; the app calls
  `VpnService.protect` and binds it to the selected physical network.

Build with `scripts/build-android-helper.sh`, Go 1.26.8 and Android NDK
29.0.14206865. Set `TUIC_GO_BIN` (or `GO_BIN`) and `ANDROID_NDK_HOME`.
Gradle builds and validates all three Direct ABIs; Play excludes the helper.

sing-box is licensed under GPL-3.0-or-later.
