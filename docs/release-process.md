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
4. Dependency resolution is reproducible, and dependency changes have been
   reviewed.
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

## Build sequence

Use JDK 17, Android SDK 36, and the committed Gradle wrapper. The validation
order is:

```text
./scripts/ci/check-repository-policy.sh
./scripts/ci/check-action-pins.sh
./scripts/ci/fetch-libxray.sh
cd levik_vpn_android
./gradlew lint
./gradlew bundleRelease
./gradlew test
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
