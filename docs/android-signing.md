# Android Signing and Native Input

Android signing identity is a production continuity boundary. Users can update
an installed application only when the update satisfies the platform's signing
rules. Never generate or substitute a new key before identifying the
certificate used for already distributed builds and deciding the migration
path.

## Signing policy

- Enroll the application in Play App Signing before public distribution.
- Keep the upload key outside Git, source archives, container images, Gradle
  properties committed to the repository, and ordinary workstation backups.
- Release configuration must fail closed if the keystore, alias, or passwords
  are absent or invalid. Debug signing is forbidden for release variants.
- Decode CI keystore material only into the runner's temporary directory, set
  restrictive permissions, and remove it when the job ends.
- Protect the signing job with a GitHub release environment and required human
  approval. Pull-request workflows never receive signing secrets.
- Verify the produced artifact with Android signing tools and compare the
  certificate SHA-256 digest to an independently recorded expected value.

Suggested GitHub secret names are
`ANDROID_UPLOAD_KEYSTORE_BASE64`, `ANDROID_UPLOAD_STORE_PASSWORD`,
`ANDROID_UPLOAD_KEY_ALIAS`, and `ANDROID_UPLOAD_KEY_PASSWORD`. Record the
expected public certificate digest as a protected variable, not as trust in the
artifact being checked.

## libXray input

The build requires the official libXray `v26.7.28` Android artifact. CI must
download the exact release asset, validate the archive before extraction,
extract only the expected AAR, and validate the AAR again.

- Release archive SHA-256:
  `28b7dc9d6cc8455fcca5cbd56e387003a7bfb558128651a64899dc3a8ccff666`
- Extracted AAR SHA-256:
  `4708a361a74f7e955635dbe3661cefb459bdc867423c3b1826a2c5a6ea4ac77d`

`scripts/ci/fetch-libxray.sh` downloads the exact official
`libxray-android.zip` asset, checks its recorded size and SHA-256, extracts only
the expected AAR, and checks the AAR SHA-256 before installing it. A cache hit
never bypasses digest verification.

## Release evidence

Retain source commit/tag, version code/name, signed AAB and optional APK
checksums, certificate digest, libXray identifiers, dependency lock state,
SBOM, complete notices, mapping file, native symbols, build provenance, and
closed-track test result. Publish artifacts through the release system rather
than committing them to Git.
