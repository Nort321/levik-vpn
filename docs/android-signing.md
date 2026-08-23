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

## Existing-install audit (2026-08-23)

The retained APKs for versions `1.2.0`, `1.3.0`, `1.4.1`, `1.5.0`, `1.6.0`,
`1.8.1`, and `1.9.0` were independently inspected with `apksigner`. Every APK
uses the same Android debug certificate:

- subject: `C=US, O=Android, CN=Android Debug`;
- SHA-256: `c1f0cd8239bdb8d2e3802c14d4cbbd7a9cc74a74db78af046985fb1756ed0612`;
- SHA-1: `62b41e072c7b959441165c729bc72883beab1578`.

The matching private key is still available in the controlled local release
environment. It must never be copied into this repository or GitHub. This is a
continuity fact, not approval to keep using a debug identity for production.

Consequently, generating a new production key and signing the next Direct APK
with it would make that APK incompatible with the installed versions above.
Binary release remains blocked until the owner selects and tests a migration
strategy.

## Signing migration decision gate

The release owner must explicitly approve one of these paths and record the
decision in the release evidence:

1. Temporarily preserve the existing signer for Direct updates. This maintains
   compatibility but keeps a debug identity in the trust chain and is not an
   acceptable steady-state production configuration.
2. Establish a protected production key and a platform-supported signing
   lineage. APK Signature Scheme v3 key rotation applies on Android 9 and newer,
   while v3.1 can target the rotated signer to Android 13 and newer. Devices on
   older Android versions can still require artifacts signed by the original
   signer. This path therefore needs both keys, signer-lineage verification,
   split testing across Android 8 through 16, and an explicit long-term policy.
3. Move to a new signing identity or package without continuity. This requires
   a deliberate uninstall/data-migration and user-communication plan; it is not
   an in-place update.

Play App Signing is a separate trust boundary. Do not assume that a Direct
signing lineage can be imported into or used by Google Play without validating
the Play Console enrollment and upgrade-key rules for the exact application.
The authoritative platform references are the Android documentation for
[APK Signature Scheme v3](https://source.android.com/docs/security/features/apksigning/v3),
[v3.1](https://source.android.com/docs/security/features/apksigning/v3-1), and
[`apksigner`](https://developer.android.com/tools/apksigner).

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
