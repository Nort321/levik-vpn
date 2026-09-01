# Levik WhiteList Relay transport/node source

This directory is an undeployed, reproducible fork layer for a matched WDTT
Plus v15 server and Android-native client plus an independent node-agent.

- `fork/wdtt-plus-v15`: GPL-3.0-only exact-v15 fork with Levik WRAP v2,
  file-only bootstrap secrets, isolated local admin IPC, and Android control
  channels.
- `node-agent`: AGPL-3.0-only idempotent lease controller.
- `contracts`: authoritative node OpenAPI, HMAC vector, response examples and
  encrypted-profile node metadata schema.
- `source`: immutable upstream and toolchain locks.
- `scripts`: source verification and reproducible build/test entry points.

Android output is exactly
`build/android/jniLibs/<abi>/liblevikrelay.so` for `arm64-v8a`, `armeabi-v7a`
and `x86_64`. The script requires NDK `29.0.14206865` but invokes only the
API-26 compilers with Go 1.26.5 and emits only PIE (`ET_DYN`) executables with
the Android linker and 16 KiB LOAD alignment. It fails instead of silently
switching to android29. The app
must package/extract the ELF and execute it with only
`-levik-control-sock=@levik_wlr_<random>` in argv.

The current checked-in layer is not deployed. Run `scripts/verify-upstream.sh`,
`scripts/test.sh`, `scripts/build-linux.sh`, and
`scripts/build-android-client.sh` in a clean CI environment before producing a
release. Runtime secrets must be supplied by root-only files, never argv or
environment variables.

## Current v1 boundaries

- A node has a fixed `10.66.66.0/24` WireGuard pool (`.2` through `.250`), so
  `--max-passwords` is validated as `1..249`; invalid CLI or persisted values
  fail startup instead of being clamped. Horizontal sharding is intentionally
  outside this single-node layer.
- Apply and rotate leases expire no later than 24 hours after node time. The
  backend renews them with monotonically increasing revisions. Every created
  WDTT entry is retained for 48 hours after expiry by default (configurable
  from 1 hour through 7 days) so an ordinary renewal can reactivate it instead
  of losing its identity at the expiry boundary. Retained expired entries
  consume the same 249-entry node capacity, so grace and provisioning rate
  must be capacity-planned together.
- Agent state schema v2 stores the non-secret credential revision separately
  from request idempotency metadata. If a WDTT record disappears while durable
  state remains, apply/rotate reconstructs the same HMAC-derived credential and
  retention deadline; renewal never silently rotates it. A monotonic apply
  after a revoked tombstone performs a fresh deterministic credential reissue
  and replaces the old password before activation. Missing-record revoke still
  advances the durable tombstone revision. Responses with `state=absent` omit
  `expiresAt` so stale lifecycle metadata is not exposed as upstream truth.
- The admin label index and WRAP v2 credential lookup are O(1), but the
  upstream credential database and agent state are crash-safe JSON files.
  Replacing those stores with transactional SQLite/PostgreSQL is required
  before raising per-node capacity beyond this bounded v1 profile.
- Levik mode disables MASQUE and system-DNS fallback. Every enabled external
  TURN, VK-auth, captcha, and direct-DNS socket must receive Android
  `protect()` plus cellular `bindSocket()` acknowledgement before connect.
- Host forwarding/NAT mutation is disabled by default. Declarative nftables
  verification requires the dedicated `levik_nat/postrouting` and
  `levik_filter/forward` chains, a DROP forward policy, stateful rules, and
  the exact comments documented by the server tests.
