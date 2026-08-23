# Direct GitHub OTA contract

The Direct distribution checks the public stable release endpoint
`https://api.github.com/repos/Nort321/levik-vpn/releases/latest` without an API
token. A response marked as a draft or prerelease is rejected. Play builds
contain neither the GitHub OTA implementation nor APK installation components.

## Release assets

The newest stable release must contain all three assets:

- the Direct APK referenced by the signed manifest;
- `update.json`;
- `update.json.sig`.

`update.json` is UTF-8 JSON with this schema:

```json
{
  "schemaVersion": 1,
  "packageName": "com.leviknet.vpn",
  "versionCode": 20,
  "versionName": "2.0.0",
  "apkUrl": "https://github.com/Nort321/levik-vpn/releases/download/v2.0.0/LevikVPN-direct-2.0.0.apk",
  "apkSize": 203423412,
  "apkSha256": "64-lowercase-hex-characters",
  "signingCertificateSha256": "64-lowercase-hex-characters",
  "changelogRu": "Описание релиза",
  "changelogEn": "Release notes",
  "forceUpdate": false
}
```

`packageName`, `versionCode`, `versionName`, APK byte size, SHA-256, and APK
signing certificate are checked again against the downloaded archive before the
system package installer can be opened. `versionCode` must be greater than the
installed code. Cross-package updates and downgrades are rejected. `forceUpdate`
defaults to `false` and never removes the user's ability to dismiss the dialog.

The APK URL must use HTTPS and the exact
`github.com/Nort321/levik-vpn/releases/download/` path prefix. Redirects are
bounded and accepted only for the explicit GitHub release-asset hosts in the
client.

## Detached signature

The signature contract is deliberately compatible with Android API 26:

- curve: ECDSA P-256 (`secp256r1`/`prime256v1`);
- signature algorithm: `SHA256withECDSA`;
- signed message: the exact raw bytes of the published `update.json` asset;
- signature value: ASN.1 DER ECDSA signature;
- `update.json.sig`: ASCII standard Base64 of those DER bytes, with surrounding
  whitespace ignored;
- pinned public key: standard Base64 of the X.509 SubjectPublicKeyInfo DER
  encoding, without PEM headers.

Do not parse, reformat, append a newline to, or otherwise rewrite `update.json`
after signing. A compatible offline signing sequence is:

```shell
openssl dgst -sha256 -sign update-manifest-private.pem \
  -out update.json.sig.der update.json
openssl base64 -A -in update.json.sig.der -out update.json.sig
openssl pkey -in update-manifest-private.pem -pubout -outform DER \
  | openssl base64 -A
```

The private manifest-signing key must remain outside the repository and CI
artifacts. This project does not generate or store a production key.

The repository generator validates the signed APK and emits the exact APK
asset, manifest, Base64 DER signature, and Direct checksums:

```shell
../scripts/release/generate-direct-update-manifest.sh \
  app-direct-release.apk 20 2.0.0 v2.0.0 \
  APK_CERTIFICATE_SHA256 changelog-ru.txt changelog-en.txt \
  update-manifest-private.pem BASE64_X509_SPKI_P256_PUBLIC_KEY output-directory
```

The release tag must be exactly `v<versionName>`. The generated APK URL and APK
asset name are fixed together and must not be renamed before upload.

## Build configuration

Direct release artifacts require both values:

```properties
levik.updateManifestPublicKey=BASE64_X509_SPKI_P256_PUBLIC_KEY
levik.updateSigningCertificateSha256=LOWERCASE_APK_CERTIFICATE_SHA256
```

Equivalent CI environment variables are:

- `LEVIK_UPDATE_MANIFEST_PUBLIC_KEY`;
- `LEVIK_UPDATE_SIGNING_CERTIFICATE_SHA256`.

Gradle exposes these values only to the Direct source set as
`BuildConfig.DIRECT_UPDATE_MANIFEST_PUBLIC_KEY` and
`BuildConfig.DIRECT_UPDATE_SIGNING_CERTIFICATE_SHA256`; they are generated from
the properties or environment variables above and must not be set separately.

`validateDirectReleaseOtaConfiguration` decodes the public key, requires P-256,
validates the fingerprint format, and compares the certificate pin with the
certificate read from the configured release keystore and alias. Missing or
mismatched values fail Direct `assemble`, `bundle`, `package`, and signing tasks.

## Client scheduling

Successful silent checks are limited to once every 18 hours. GitHub ETags and a
small atomic metadata cache are used for conditional requests. Offline and
transient errors use bounded exponential backoff; HTTP 403/429 respect GitHub
rate-limit headers, and HTTP 404 backs off for 12 hours. Manual failures are
reported without blocking normal VPN use.

GitHub's unauthenticated Releases API and assets are unavailable to end users
while `Nort321/levik-vpn` is private. The release workflow therefore creates a
draft for review, but Direct OTA becomes operational only after the repository
and stable release assets are anonymously readable. Embedding a repository
token in the app is prohibited.
