# Levik VPN for Android

Native Kotlin/Jetpack Compose client for Levik Account and the Xray-based VPN
network. Capable backend challenges open the exact verified
`https://leviknet.com/activate` route in an AndroidX Browser Custom Tab. A
flagged legacy response can still use the 1.9 Telegram device flow. The app
binds sessions to an Android Keystore RSA key, signs every mobile BFF request,
stores tokens/configuration only as AES-GCM ciphertext, and runs the official
libXray core behind Android `VpnService`.

## Local configuration

Use untracked `local.properties` values:

```properties
sdk.dir=/absolute/path/to/Android/sdk
levik.cabinetBaseUrl=https://leviknet.com
levik.playIntegrityCloudProjectNumber=123456789012
```

Release signing is independent for each distribution. Direct uses
`levik.direct.signing.storeFile`, `storePassword`, `keyAlias`, and
`keyPassword`; Play uses the same fields under `levik.play.signing.*`.
Equivalent environment variables are `LEVIK_DIRECT_SIGNING_STORE_FILE`,
`LEVIK_DIRECT_SIGNING_STORE_PASSWORD`, `LEVIK_DIRECT_SIGNING_KEY_ALIAS`,
`LEVIK_DIRECT_SIGNING_KEY_PASSWORD`, and the corresponding `LEVIK_PLAY_*`
names. Never commit those values or keystores. Each release artifact fails
closed if its own complete signing identity is unavailable.

Direct GitHub OTA release artifacts additionally require
`levik.updateManifestPublicKey` and
`levik.updateSigningCertificateSha256` (or
`LEVIK_UPDATE_MANIFEST_PUBLIC_KEY` and
`LEVIK_UPDATE_SIGNING_CERTIFICATE_SHA256`). The Direct release task verifies
that the certificate pin matches the configured release keystore. See
`docs/direct-ota.md` for the exact `update.json` and detached-signature format.

Add the verified native core as described in `app/libs/README.md`. Debug source
compilation may be used for UI work, but a VPN runtime and every release build
require the real AAR.

## Mobile BFF contract

Requests use a device-negotiated Android Keystore signature (`PS256` on API 35+
with explicit MGF1 SHA-256 support, `RS256` on API 26–34) over this canonical
UTF-8 payload:

```text
v1
METHOD
/api/mobile/v1/path
EPOCH_SECONDS
NONCE_BASE64URL
DEVICE_ID
SHA256_HEX_ACCESS_TOKEN_OR_EMPTY
SHA256_HEX_BODY
```

The server must derive `deviceId` as lowercase SHA-256 of SPKI DER, enforce a
short clock window, consume every nonce once, verify session/device ownership,
and never return a raw subscription URL. Tunnel profiles use
`RSA-OAEP-256+A256GCM` on API 35+ and `RSA-OAEP+A256GCM` on API 26–34; the
algorithm is declared during the signed challenge and pinned to the session.

## Build

Use JDK 17 and Android SDK 36:

```shell
./gradlew verifyAllDependencyLocks
./gradlew lintDirectRelease lintPlayRelease
./gradlew assembleDirectRelease bundlePlayRelease
./gradlew testDirectReleaseUnitTest testPlayReleaseUnitTest
./gradlew :app:cyclonedxDirectBom
```

The release task fails when either the pinned AAR or release signing is absent.
Play release also fails without a Play Integrity Cloud project number; Direct
release fails without valid OTA public-key and signing-certificate pins. The mobile BFF
must decode every `X-Levik-Integrity` token and compare its request hash with
the canonical signed request; the client token alone is not a security verdict.
See `RELEASE_CHECKLIST.md` before any Play upload.

Dependency locks live in `gradle.lockfile` and `app/gradle.lockfile`. Regenerate
them only after a reviewed dependency change with
`./gradlew resolveAndLockAllDependencies --write-locks`; normal CI resolves all
project dependency configurations in strict mode (excluding AGP's synthetic
SDK-only `androidApis` configuration). The official CycloneDX plugin 3.4.1 emits the
Gradle JSON/XML SBOM, while `../scripts/release/generate-native-sbom.py`
inventories the otherwise opaque libXray AAR and its embedded Go module graph.

The manual `.github/workflows/android-release.yml` job is protected by the
`production-release` environment, requires an explicit current-signing-identity
decision and separate Direct/Play secrets, and creates only a draft release.
It cannot make Direct OTA usable while the GitHub repository is private:
installed apps intentionally contain no GitHub token, so stable release assets
must eventually be anonymously readable.
