# Android mobile API v1

All endpoints are HTTPS-only, return `Cache-Control: no-store`, and use
unpadded base64url for binary values.

## Request proof

Every request has these headers:

- `X-Levik-Device-Id`: lowercase hex SHA-256 of the RSA SPKI DER;
- `X-Levik-Timestamp`: Unix epoch seconds;
- `X-Levik-Nonce`: 16 random bytes encoded as base64url;
- `X-Levik-Signature`: RSA-3072 signature using the negotiated algorithm.

Authenticated endpoints also have `Authorization: Bearer <accessToken>`.
The signed UTF-8 canonical value is:

```text
v1
METHOD
/exact/path
TIMESTAMP_EPOCH_SECONDS
NONCE_BASE64URL
DEVICE_ID
SHA256_HEX(accessToken-or-empty)
SHA256_HEX(exact-body-bytes)
```

The timestamp window is two minutes and each nonce is accepted once.
Android API 35+ negotiates `PS256` (PSS-SHA256 with a 32-byte salt);
API 26-34 negotiates `RS256` because those Keystore releases cannot pin the
PSS MGF1 digest to SHA-256. The selected algorithm is bound at registration
and cannot be changed by later requests.

## Authentication

`POST /api/mobile/v1/auth/challenge`

```json
{
  "publicKeySpki": "<base64url DER>",
  "deviceLabel": "Nikita's Pixel",
  "deviceModel": "Pixel 10 Pro",
  "deviceOs": "Android 16",
  "appVersion": "1.0.0",
  "requestSigningAlgorithm": "PS256",
  "profileEncryptionAlgorithm": "RSA-OAEP-256+A256GCM",
  "accountActivationSupported": true
}
```

`accountActivationSupported` is part of the signed body. `true` selects the
bridge-independent Levik Account activation flow. Missing or `false` preserves
the legacy Telegram flow for 1.9 clients.

The account-capable response is:

```json
{
  "ok": true,
  "loginToken": "<opaque>",
  "accountActivationSupported": true,
  "activationCode": "ABCD-EFGH-JKMN-PQRS",
  "activationUriComplete": "https://leviknet.com/activate?code=...",
  "pollIntervalSeconds": 2,
  "expiresAt": "2026-07-29T12:00:00.000Z"
}
```

It is created locally with a ten-minute lifetime and does not call the
Telegram bridge. The echoed `accountActivationSupported: true` is the response
discriminant; it is omitted entirely from legacy responses for compatibility.
The legacy successful response remains:

```json
{
  "ok": true,
  "loginToken": "<opaque>",
  "verificationCode": "ABCD23",
  "verificationUriComplete": "https://t.me/...",
  "pollIntervalSeconds": 5,
  "expiresAt": "2026-07-29T12:00:00.000Z"
}
```

`POST /api/mobile/v1/auth/status` takes `{"loginToken":"<opaque>"}`.
The login token is not a Bearer token, so the canonical access-token field is
the SHA-256 of an empty string.

Pending:

```json
{"ok":true,"state":"pending","pollIntervalSeconds":5}
```

Authenticated:

```json
{
  "ok": true,
  "state": "authenticated",
  "accessToken": "<opaque>",
  "expiresAt": "2026-08-28T12:00:00.000Z"
}
```

Current reliability limitation: the login attempt is consumed when the server
creates the authenticated session, before the client can acknowledge receipt.
If that authenticated HTTP response is lost, a retry can return `login_expired`
and the client must start a new challenge. The server does not persist or replay
the plaintext access token. Adding encrypted, device-bound replay requires an
additive protocol and migration and is intentionally outside this release.

`POST /api/mobile/v1/auth/logout` takes `{}` and requires the signed Bearer
session.

## Account and tunnel profile

`GET /api/mobile/v1/account` returns the validated cabinet snapshot without
subscription or payment URLs.

`POST /api/mobile/v1/tunnel-profile` takes
`{"subscriptionId":"<uuid>"}`. The server verifies account ownership and
status, sends the bound Remnawave HWID headers, refuses redirects, and reads at
most 1 MiB from the configured HTTPS subscription origins.

The response is `{"ok":true,"profile":<envelope>}`. The envelope is:

```json
{
  "algorithm": "RSA-OAEP-256+A256GCM",
  "encryptedKey": "<base64url>",
  "iv": "<base64url>",
  "ciphertext": "<base64url ciphertext followed by the 16-byte GCM tag>",
  "aad": "<base64url>"
}
```

Android API 35+ uses `RSA-OAEP-256+A256GCM` (OAEP and MGF1 SHA-256).
API 26-34 uses `RSA-OAEP+A256GCM` (OAEP and MGF1 SHA-1), which matches those
Keystore versions. Both variants use a fresh random AES-256-GCM content key;
request authentication remains SHA-256 and the RSA key remains 3072 bits.

After decryption, the profile is:

```json
{
  "version": 1,
  "profileId": "<sha256 hex>",
  "subscriptionId": "<uuid>",
  "issuedAt": "2026-07-29T12:00:00.000Z",
  "subscriptionExpiresAt": "2026-08-29T12:00:00.000Z",
  "source": {
    "mediaType": "text/plain",
    "content": "<bounded raw subscription response>"
  },
  "routing": {
    "directCidrs": ["10.0.0.0/8"]
  }
}
```

The encrypted profile has no cache TTL and is stored separately from the
authentication session in Android no-backup storage. Expiration or invalidation
of the Bearer session, including an API `401`, requires renewed authentication
for account and profile API calls but must not delete the cached VPN profile.
The cached profile is removed only by explicit logout, an account change,
profile replacement, or app uninstall.

Authentication state alone does not invalidate an existing cached profile.
Android may use it to connect while `subscriptionExpiresAt` is `null` or is
still in the future. A non-null `subscriptionExpiresAt` contains only the actual
subscription entitlement expiry; Android must refuse a connection after that
instant without deleting the stored profile. Server-side VPN credential and
HWID revocation remain authoritative. The account endpoint returns the same
entitlement expiry.

While authentication and the network are available, Android may
opportunistically fetch and atomically replace the profile so node, key, and
LTE configuration changes are delivered. `issuedAt` can schedule that refresh,
but it is not a cache TTL: a failed refresh, `401`, or unavailable attestation
must leave the last successfully prepared profile usable until the actual
subscription expiry.

The backend deliberately does not rewrite the subscription source. Android
passes it to the pinned libXray converter so XHTTP `extra`, XMUX, packet-up,
and current/legacy session-placement fields remain compatible.

## Play Integrity activation

`MOBILE_PLAY_INTEGRITY_REQUIRED` defaults to `false`. In this optional mode a
missing `X-Levik-Integrity` header is accepted for Direct builds, while any
provided token is always verified and an invalid token is rejected. When the
flag is enabled, a missing token is also rejected; do not enable that global
mode while Direct and Play builds share this endpoint. The expected Play
`requestHash` is the base64url SHA-256 of the canonical signed request. The
normalized verifier also pins package name, Play signing certificate,
licensing, app recognition, device integrity, and verdict time.

The server decodes standard tokens only through Google's
`decodeIntegrityToken` endpoint. Production activation requires the linked
service-account email and its RSA PKCS#8 PEM encoded as unpadded base64url in
`MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_EMAIL` and
`MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_PRIVATE_KEY_BASE64`. No credential is
stored in the repository. Required mode fails closed at environment validation
when the certificate digest or service-account credential is absent. Optional
mode still needs those credentials and certificate digests in production when
Play builds send tokens; otherwise their requests fail closed with `503`.

All failures use:

```json
{"ok":false,"error":{"code":"machine_readable_code","retryable":false}}
```
