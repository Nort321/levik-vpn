# Third-Party Notices

This file records known third-party components and release obligations. It is
not yet a complete generated notice bundle or SBOM. Every release must derive a
complete inventory from the resolved dependency graph and shipped artifact.

The repository's `AGPL-3.0-only` grant applies only to original project code.
It does not relicense the components below, dependency source, generated files,
binary artifacts, trademarks, local backups, or code that is not included in
the repository. Each third-party component remains subject to its own license.

## Android native VPN runtime

### libXray

- Upstream: <https://github.com/XTLS/libXray>
- Pinned release: `v26.7.28`
- License identified by the project: MIT
- Release archive SHA-256:
  `28b7dc9d6cc8455fcca5cbd56e387003a7bfb558128651a64899dc3a8ccff666`
- Extracted Android AAR SHA-256:
  `4708a361a74f7e955635dbe3661cefb459bdc867423c3b1826a2c5a6ea4ac77d`

The AAR is an external build input and is intentionally excluded from Git.

### Xray-core

- Upstream: <https://github.com/XTLS/Xray-core>
- Version: the version incorporated by the pinned libXray release
- License identified by the project: Mozilla Public License 2.0

Release engineering must preserve the exact upstream source reference,
applicable notices, and information required for recipients to obtain covered
source and modifications.

### SagerNet sing libraries embedded in libXray

Go build metadata and native symbols in the pinned AAR identify the following
linked components:

- `github.com/sagernet/sing` `v0.5.1` — GPL-3.0-or-later;
- `github.com/sagernet/sing-shadowsocks` `v0.2.7` — GPL-3.0-or-later.

These are executable components, not merely development-only module records.
Distribution of an APK/AAB containing them requires the applicable GPL text,
notices, and machine-readable Corresponding Source under GPLv3 section 6. The
source offer must cover the exact revisions, build scripts, interface files,
and any local changes needed for the shipped native binary. A generated Java
sources JAR or a link to a moving upstream branch is not sufficient.

### Country IP blocks

- Upstream: <https://github.com/herrbischoff/country-ip-blocks>
- Snapshot recorded by the Android project: 2025-05-16
- License identified by the project: CC0 1.0 Universal

The Android assets record their source reference in
`levik_vpn_android/app/src/main/assets/RU_CIDR_SOURCE.txt`.

## Android application dependencies

AndroidX, Kotlin, kotlinx.coroutines, kotlinx.serialization, Google Play
libraries, and their transitive dependencies retain their respective upstream
licenses and distribution terms. Direct dependency declarations are not a
substitute for a generated release inventory.

## Missing and excluded components

The local `levik_vpn_landing/` worktree and all website, backend, mobile BFF,
bot, bridge, database, deployment, production configuration, and historical
backup material are excluded from this Android-only repository. They are not
distributed or licensed by this notice, and no statement here grants them
open-source status. Telegram session state, databases, credentials, and
compiled artifacts are never source-code contributions.

## Release requirement

Before Android distribution, generate and review an SBOM and complete license
notice bundle for the exact AAB/APK and native libraries. Preserve that
inventory with the release provenance. Binary publication is blocked until the
libXray/Xray-core/sing source closure and corresponding license texts have been
assembled and verified for the shipped artifact.
