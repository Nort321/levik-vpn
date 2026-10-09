# TUIC helper for Direct Android

Xray (libXray) has no TUIC client. The Direct distribution runs a small TUIC
client as a loopback SOCKS5 sidecar: Xray keeps the TUN, routing and DNS, and
forwards the selected TUIC server's traffic to the sidecar.

- Source: sing-box `v1.14.2`, pinned by archive SHA-256 in
  `scripts/build-android-helper.sh` (see `source/upstream.json`).
- Command: `source/levik-tuic/main.go`, copied into that tree as
  `cmd/levik-tuic` and built against the release's own `go.mod`/`go.sum`. It
  links only sing-quic's TUIC client and sing's SOCKS5 server (about 9 MB per
  ABI instead of ~42 MB for the full sing-box binary) and accepts the subset of
  the sing-box configuration format the app writes (one authenticated loopback
  `socks` inbound, one `tuic` outbound with a pinned CA and `protect_path`).
- Socket protection: the sidecar sends every outbound socket to the app over
  the `protect_path` abstract Unix socket; the app calls
  `VpnService.protect` and binds it to the selected physical network.

Build with `scripts/build-android-helper.sh`, Go 1.26.8 and Android NDK
29.0.14206865. Set `TUIC_GO_BIN` (or `GO_BIN`) and `ANDROID_NDK_HOME`.
Gradle builds and validates all three Direct ABIs; Play excludes the helper.

sing-box is licensed under GPL-3.0-or-later.
