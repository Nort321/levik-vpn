# Third-party notices

## libXray

- Project: <https://github.com/XTLS/libXray>
- Pinned release: `v26.7.28`
- License: MIT
- Android release archive SHA-256:
  `28b7dc9d6cc8455fcca5cbd56e387003a7bfb558128651a64899dc3a8ccff666`
- Extracted Android AAR SHA-256:
  `4708a361a74f7e955635dbe3661cefb459bdc867423c3b1826a2c5a6ea4ac77d`

Copyright and license text are provided by the upstream release and repository.

## Xray-core

- Project: <https://github.com/XTLS/Xray-core>
- Version: the Xray-core version pinned by libXray `v26.7.28`
- License: Mozilla Public License 2.0

Source-code availability and modification notices required by MPL-2.0 must be
preserved in every distributed build. The release pipeline must archive the
exact upstream source references and any local modifications alongside the
corresponding application release.

## SagerNet sing libraries embedded in libXray

The pinned native AAR contains linked code and Go build metadata for:

- `github.com/sagernet/sing` `v0.5.1` — GPL-3.0-or-later;
- `github.com/sagernet/sing-shadowsocks` `v0.2.7` — GPL-3.0-or-later.

Every distributed APK/AAB containing these native libraries must provide the
applicable GPL text and notices plus machine-readable Corresponding Source as
required by GPLv3 section 6. The source bundle must cover the exact revisions,
build scripts, interface files, and local modifications needed for the shipped
binary. A generated Java sources JAR alone is not Corresponding Source.

## Country IP blocks

- Project: <https://github.com/herrbischoff/country-ip-blocks>
- Russian IPv4/IPv6 snapshot: 2025-05-16
- License: CC0 1.0 Universal

The bundled CIDR files are used only for local VPN routing decisions. Source
URLs are recorded in `app/src/main/assets/RU_CIDR_SOURCE.txt`.

## AndroidX, Kotlin, kotlinx, and Google libraries

These dependencies retain their respective upstream licenses and distribution
terms. The generated Play and Direct artifacts must include a reviewed notice
inventory produced from each resolved dependency graph.

Android binary publication is blocked until the full native source closure,
license texts, SBOM, and artifact-specific notice bundle have been verified.
