# Levik Game helper for Direct Android

Levik Game on Android works like the Windows client: Xray keeps the TUN and
routes the UDP of the selected games to a loopback VLESS/XUDP listener of this
sidecar (`liblevikgame.so`). The sidecar carries each datagram to the game
relay of one exit node over Xray's own authenticated SOCKS inbounds (one per
game server of the profile, `levik-game` user), directly or through another
node's mesh, sends a copy over the two best paths and hands the first reply
back to the game. Frames are the shared LGM2/LGM3 format (`source/wire`, the
same golden vectors as the relay and the Windows client); the profile's signed
`relayToken` is sent to every exit (`KindAuth`).

- Source: `source/cmd/levik-game` and `source/game`, Go standard library only.
- Configuration: JSON on stdin (`game.Config`: listen port and UUID, SOCKS
  password, routes, node states, preferred exit, relay token); nothing is
  written to disk. stdout: `ready <port>`, then a status line every 2 s.
- Every socket is loopback: Xray owns, protects and binds the real ones.

Build with `scripts/build-android-helper.sh`, Go 1.26.8 (`GAME_GO_BIN` or
`GO_BIN`) and Android NDK 29.0.14206865 (`ANDROID_NDK_HOME`). Gradle builds and
validates all three Direct ABIs; Play excludes the helper.

Tests: `go test -race ./...` in `source`. The end-to-end tests run against the
production relay when `LEVIK_TEST_GO_RELAY` (or the sibling
`levik_game_node/dist/levik-game-relay-test` from `make cross-test`) exists.
