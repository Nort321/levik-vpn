# libXray Android artifact

Release and VPN runtime builds require:

`app/libs/libXray.aar`

Use the official XTLS/libXray `v26.7.28` Android release and verify the release
archive before extracting the AAR:

- Release: <https://github.com/XTLS/libXray/releases/tag/v26.7.28>
- Archive SHA-256:
  `28b7dc9d6cc8455fcca5cbd56e387003a7bfb558128651a64899dc3a8ccff666`
- Extracted `libXray.aar` SHA-256:
  `4708a361a74f7e955635dbe3661cefb459bdc867423c3b1826a2c5a6ea4ac77d`

The AAR is intentionally ignored by Git. CI should download it from the pinned
release, verify the digest, and copy only `libXray.aar` into this directory.
Never silently substitute another core version: libXray does not guarantee API
stability and tracks the latest compatible Xray-core release.

From the repository root, run `scripts/ci/fetch-libxray.sh` to perform the
pinned download and both digest checks.
