# Levik VPN for Android

Native Kotlin/Jetpack Compose client for the Levik VPN cabinet and Xray-based
network. The app authenticates through the existing Telegram device flow, binds
sessions to an Android Keystore RSA key, signs every mobile BFF request, stores
tokens/configuration only as AES-GCM ciphertext, and runs the official libXray
core behind Android `VpnService`.

## Local configuration

Use untracked `local.properties` values:

```properties
sdk.dir=/absolute/path/to/Android/sdk
levik.cabinetBaseUrl=https://leviknet.com
levik.playIntegrityCloudProjectNumber=123456789012
```

Release signing also requires `levik.signing.storeFile`,
`levik.signing.storePassword`, `levik.signing.keyAlias`, and
`levik.signing.keyPassword`. Prefer CI environment variables and never commit
those values.

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
./gradlew lintDirectRelease lintPlayRelease
./gradlew assembleDirectRelease bundlePlayRelease
./gradlew testDirectReleaseUnitTest testPlayReleaseUnitTest
```

The release task fails when either the pinned AAR or release signing is absent.
It also fails without a Play Integrity Cloud project number. The mobile BFF
must decode every `X-Levik-Integrity` token and compare its request hash with
the canonical signed request; the client token alone is not a security verdict.
See `RELEASE_CHECKLIST.md` before any Play upload.
