# Android Release Process

This repository is prepared for a private-first Android release workflow. A
future public-source or binary-publication decision is separate and may occur
only after the gates below are complete. A local APK/AAB, generated directory,
or historical artifact is not a release.

## Release gates

1. The source revision is reviewed and identified by an immutable
   `vX.Y.Z` tag created from `main`.
2. Repository policy and Android CI checks pass without production secrets.
3. `scripts/ci/fetch-libxray.sh` installs the pinned official native input and
   both recorded SHA-256 checks succeed.
4. Dependency resolution is reproducible, dependency changes have been
   reviewed, and both Gradle lockfiles resolve in strict mode. AGP's synthetic
   SDK-only `androidApis` configuration is explicitly excluded from locking.
5. Production signing inputs are supplied only to an approved release
   environment; release tasks fail if any input is absent or invalid.
6. The signing certificate digest matches an independently controlled expected
   value.
7. The exact artifact has an SBOM, complete third-party notice bundle,
   applicable license texts, and verified corresponding-source closure.
8. Release lint, build, unit tests, relevant instrumentation tests, and the
   device/network test matrix pass.
9. The first upload uses an internal or closed test track and is promoted only
   after review of test and telemetry evidence.

Binary distribution is blocked while any gate is incomplete.

The source-closure gate is implemented by
`scripts/release/build-corresponding-source.sh`. It verifies the pinned AAR,
the exact audited upstream source archives, and the Go build metadata; captures
the clean Android repository revision; vendors the pinned Go graph; preserves
the complete Go module cache, upstream archives, build scripts, notices, and
Go toolchain source; and emits a deterministic compressed bundle plus SHA-256.
The public inputs are locked in `release/native-sources.lock.json`.

`./gradlew :app:cyclonedxDirectBom` uses the official CycloneDX Gradle plugin 3.4.1 to
emit JSON and XML for the Direct and Play release runtime graphs. Because a
flat AAR is opaque to Gradle metadata, `scripts/release/generate-native-sbom.py`
separately verifies its pinned digest, extracts the embedded Go build metadata,
and emits JSON/XML inventory for libXray, every recorded Go module, and the
digest-locked source archives. Review both SBOM sets together.

This closes source availability for the audited native input, but it does not
turn the upstream binary into a bit-for-bit reproducible build. Publication is
still blocked until the generated bundle, license inventory, and artifact SBOM
have been reviewed together and the application-signing migration gate in
`docs/android-signing.md` has an owner-approved decision.

`scripts/release/generate-direct-update-manifest.sh` derives release metadata
from the signed Direct APK, verifies package/version/size/hash and the protected
certificate SHA-256 value, requires a matching ECDSA P-256 manifest key pair,
signs the exact UTF-8 `update.json` bytes with `SHA256withECDSA`, verifies its
own detached signature, and emits `update.json.sig` as Base64 DER plus a
`SHA256SUMS` file. The manifest private key is independent from Android package
signing and must be supplied only through the protected release environment.

## Build sequence

Use JDK 17, Android SDK 36, and the committed Gradle wrapper. The validation
order is:

```text
./scripts/ci/check-repository-policy.sh
./scripts/ci/check-action-pins.sh
./scripts/ci/fetch-libxray.sh
cd levik_vpn_android
./gradlew verifyAllDependencyLocks
./gradlew lintDirectRelease lintPlayRelease
./gradlew assembleDirectRelease bundlePlayRelease
./gradlew testDirectReleaseUnitTest testPlayReleaseUnitTest
./gradlew :app:cyclonedxDirectBom
```

Run instrumentation and physical-device tests for release-relevant VPN,
network handoff, restart, reboot, and OEM power-management behavior. The
component checklist in `levik_vpn_android/RELEASE_CHECKLIST.md` remains a
mandatory companion to this root process.

## Signing

Release signing must use the established production identity described in
`docs/android-signing.md`. Store keys and passwords outside Git. Pull-request
jobs never receive them. After building, verify the AAB/APK signature and
certificate digest with Android tooling before upload.

Direct and Play take separate `levik.direct.signing.*` and
`levik.play.signing.*` inputs (or their `LEVIK_DIRECT_SIGNING_*` and
`LEVIK_PLAY_SIGNING_*` environment equivalents). Separate configuration does
not decide whether the real certificates should match. That owner decision is
an update-continuity gate and the manual workflow refuses to proceed while its
`signing_identity_decision` input remains `blocked`.

`.github/workflows/android-release.yml` is manual-only, uses the protected
`production-release` environment, validates all required secrets before build,
accepts only an exact stable `vX.Y.Z` tag contained in `origin/main`, permits
only the repository owner to dispatch it, and requires `versionCode` to exceed
both the known installed baseline (19) and every reachable stable tag. It
attests every release output before creating a draft GitHub release rather than
publishing one. The Direct OTA client deliberately has no GitHub token.
Consequently, private-repository drafts are review evidence only; end-user OTA
remains unavailable until the repository and stable release assets are
anonymously readable.

## Release evidence

Retain the source tag and commit, version name/code, Gradle and Android toolchain
versions, resolved dependency inventory, libXray release and digests, signed
artifact checksums, signing certificate digest, SBOM, notices, corresponding
source, mapping file, native symbols, CI results, device-test results, and build
provenance.

Artifacts belong in the approved release system, never in Git. A release record
must identify its prior compatible version and the response plan for a halted
rollout or a required corrective release.

## GitHub Action pinning

Every remote GitHub Action reference must use a reviewed full 40-character
commit SHA with a version comment. Mutable major or version tags are discovery
metadata, not trust anchors. Dependency automation may propose pin updates, but
the referenced upstream release and commit must still be reviewed.
