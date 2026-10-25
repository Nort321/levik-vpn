# LevikDrop: files between your own devices

One app of an account sends files to another app of the same account: the
Android, Windows, macOS and Linux apps, in any direction. Both apps must be
running; nothing waits on a server for a device that is away. This page is
the contract every app and the relay implement byte for byte.

```
sender ──TLS──▶ drop relay ◀──TLS── receiver        (always: meeting point, fallback path)
sender ◀──────── TCP on the local network ────────▶ receiver   (when both are on one network)
```

- **Content and names never reach a server.** The two apps agree on keys
  with each other (ECDH P-256, signed by their identity keys) and encrypt
  every record with AES-256-GCM. The website keeps who sends to whom, the
  number of files, the total size and the state; the relay sees addresses,
  timing and volume (privacy policy, section 5.3).
- **Nothing is stored on the way.** The relay forwards frames as they come
  with bounded buffers (backpressure to the sender) and writes nothing.
- **Trust is the user's, not the server's.** The first time two apps meet, or
  after a reinstall changed a key, both screens show the same six digits and
  the user confirms them on both. The apps then pin each other's identity key.
- **Beta limits:** 20 files and 2 GiB per transfer, one open transfer per app,
  20 GiB a day per account through the relay (local-network bytes are not
  counted), 100 Mbit/s for the whole relay, shared equally.

## Website API

All requests are signed app requests ([Android mobile API](../levik_vpn_air/docs/mobile-api.md)).

| Request | |
|---|---|
| `PUT /api/mobile/v1/drop/presence` `{ key, protocol: 1 }` | The app can receive now. `key`: its identity key (ECDSA P-256 SPKI DER, base64url). Every 60 s while it can; ready for 150 s. Answer `{ ok, readyUntil }`. |
| `DELETE /api/mobile/v1/drop/presence` | Not ready any more (quit, sign-out, Android leaves the foreground with the VPN off). |
| `GET /api/mobile/v1/drop/devices` | `{ ok, available, devices: [{ deviceId, name, platform, appVersion, current, ready, keyId }], quota: { limitBytes, usedBytes, resetsAt }, limits: { maxFiles, maxBytes } }`. `keyId`: first 16 hex digits of SHA-256 of the identity key, null if the app never registered one. |
| `GET /api/mobile/v1/drop/transfers` | This app's transfers of the last 7 days, newest first: `{ ok, transfers: [Transfer], quota }`. |
| `POST /api/mobile/v1/drop/transfers` `{ receiverDeviceId, fileCount, totalBytes }` | Offers files. Needs an active subscription. `{ ok, transfer, connection }`. |
| `POST /api/mobile/v1/drop/transfers/update` `{ id, action, reason? }` | One step, below. `{ ok, transfer, connection }` (`connection` only for `connect`). |

```
Transfer   = { id, role: "sender"|"receiver", peerDeviceId, peerName, peerPlatform, state, reason,
               fileCount, totalBytes, relayedBytes, createdAt, updatedAt, expiresAt, finishedAt }
Connection = { token, expiresAt, endpoints: [{ host, port, pin }] }
```

Errors (`{ ok: false, error: { code, retryable } }`): `drop_unavailable` 503,
`subscription_required` 403, `device_not_found` 404, `device_not_ready` 409,
`device_busy` 409, `quota_exceeded` 429, `transfer_not_found` 404,
`transfer_ended` 409, `invalid_action` 409, `rate_limited` 429.

### States

| Action | Who | From → to |
|---|---|---|
| `connect` | both | `offered` stays; `active`/`paused` → `active`. Returns a fresh relay token (15 minutes) and pushes the deadline to 24 h. Needs an active subscription. |
| `accept` | receiver | `offered` → `active` |
| `decline` | receiver | `offered` → `declined` |
| `pause` (`reason`: `user`, `network`, `quota`) | both | `active` → `paused` |
| `complete` | both | `active`/`paused` → `done` |
| `cancel` | both | open → `cancelled` (`reason`: `sender`/`receiver`) |
| `fail` (`reason`: `source_changed`, `integrity`, `storage_full`, `storage_error`, `source_unreadable`, `protocol`, `key_rejected`) | both | open → `failed` |

An offer expires 15 minutes after it was made; an accepted transfer 24 hours
after its last `connect` or pause (`expired`). Signing out an app ends its
transfers (`cancelled`, `device_revoked`) and the relay drops them within 15 s.
Repeating the step that produced the current state answers with it. Every
change sends the `drop` [live event](app-platform.md#live-events): apps fetch
`/drop/transfers` (and `/drop/devices` if they show it).

## Relay

`levik_drop_relay` (Go, one binary) runs on its own host. The website hands
out `endpoints`: today the relay VPS port that reaches it from Russia first,
the host itself second. Apps try them in order, 6 s each.

**Transport.** TLS 1.2+ to `host:port` with no SNI requirement. The
certificate is self-signed; the app accepts only a certificate whose SPKI
SHA-256 (base64url, no padding) equals `pin`. Inside, frames are
`u32 big-endian length (1..270 336) | payload`.

**Joining.** The first frame is JSON `{"v":1,"token":"<connection.token>"}`.
The relay checks the HMAC, the expiry and that the transfer is still listed
by the website, then answers with control frames: a payload whose first byte
is `0x00`, followed by JSON.

| Control | |
|---|---|
| `{"t":"wait"}` | joined, the other app is not here yet |
| `{"t":"peer"}` | both are here: start a new session (handshake) |
| `{"t":"gone"}` | the other app left: drop the session, keep the transfer state, wait |
| `{"t":"end","reason":…}` then close | `replaced` (the same app joined again), `ended` (transfer over or revoked), `limit` (daily limit reached), `bad_token`, `full`, `idle`, `stale` (relay lost the website) |

Every other frame starts with a byte ≠ `0x00` and is forwarded verbatim to
the other app while it is there (and dropped otherwise). Apps send a `0x7F`
frame every 20 s when idle; the relay closes a connection silent for 60 s.
Bytes from the sender count against the token's `m`; the receiver may send at
most 16 MiB per connection (acknowledgements).

**Website side.** `GET /api/drop/v1/relay/state` every 15 s →
`{ ok, transfers: [id] }`; connections of transfers not listed (and joined
before that list was fetched) end with `ended`; without a list newer than
3 minutes nobody joins. `POST /api/drop/v1/relay/usage`
`{ relay, batch, usage: [{ transfer, bytes }] }` every 30 s, the same batch id
until accepted. Both with `Authorization: Bearer <HMAC-SHA256(secret, "levik-drop-relay-api:v1")>`.

**Token.** `base64url(JSON) "." base64url(HMAC-SHA256(secret, "levik-drop-token:v1|" + body))`,
claims `{ v: 1, k: "drop", t: transferId, r: "s"|"r", m: maxBytes, e: unixExpiry, n: random }`.

## Session

Every time the relay says `peer`, both apps start from scratch: a new
handshake, new keys, a new record counter. Persistent state (what was
offered, what was written) survives sessions; keys never do.

### Handshake

Messages are relay payloads; `lp(x)` is `u16 big-endian length | x`;
`id` = identity key SPKI DER; `eph` = fresh P-256 public key, uncompressed
(65 bytes); `n` = 32 random bytes; `transfer` = the 16 bytes of the UUID.

```
S → R  0x01 | 0x01 | transfer | lp(idS) | ephS | commit        commit = SHA-256("levik-drop/v1 commit\0" | lp(idS) | ephS | nS)
R → S  0x02 | 0x01 | transfer | lp(idR) | ephR | nR
S → R  0x03 | nS | lp(sigS)
R → S  0x04 | lp(sigR)

th   = SHA-256("levik-drop/v1 transcript\0" | transfer | lp(idS) | ephS | commit | lp(idR) | ephR | nR | nS)
sigS = ECDSA-P256-SHA256(idS, "levik-drop/v1 sender\0" | th)      DER
sigR = ECDSA-P256-SHA256(idR, "levik-drop/v1 receiver\0" | th)    DER
okm  = HKDF-SHA256(ikm = ECDH(eph), salt = th, info = "levik-drop/v1 keys", 96 bytes)
kSR  = okm[0..32]   kRS = okm[32..64]   kLan = okm[64..96]
code = (u32 big-endian of HMAC-SHA256(th, "levik-drop/v1 code")[0..4]) mod 1 000 000, shown "123 456"
```

The receiver checks `commit` against `nS` before it signs; each side checks
the other's signature, the transfer id and protocol version 1, and aborts the
session on any mismatch. The sender commits to `nS` before it sees `nR`, so a
relay in the middle cannot pick values that make both screens show the same
code (one chance in a million per attempt).

**Trust.** Each app pins `SHA-256(id)` of the peer per peer `deviceId`. A
peer whose key matches its pin is trusted. Otherwise (`new`, or `changed`
after a reinstall) both apps show the code; the receiver accepts only after
the user confirmed it, the sender sends no file byte before its user
confirmed it, and only then each pins the key. Confirming on one side is not
confirming on the other. Revoking a device on the website ends its transfers;
its pin stays harmless, since the website never lets it join again.

### Records

```
0x10 | seq (u64 big-endian) | AES-256-GCM(key, nonce = 0x00000000 | seq, aad = 0x10 | seq, plaintext)
```

`kSR` from the sender, `kRS` from the receiver; `seq` starts at 0 in each
direction of each session and grows by one; anything else aborts the session.
Plaintext is `0x01 | UTF-8 JSON` (control) or
`0x02 | file (u16) | offset (u64) | bytes (1..262 144)` (data).

| Control | Direction | |
|---|---|---|
| `{"t":"manifest","files":[{"name","size"}],"total"}` | S → R | first record of every session; must match the offer's count and total and, on later sessions, the first manifest exactly |
| `{"t":"accept","offsets":[n…],"prefix":[hex\|null…]}` | R → S | after the user accepted (first session) or at once (later sessions). `offsets[i]`: bytes of file *i* already written and kept (its size when done); `prefix[i]`: SHA-256 hex of those bytes when `0 < offsets[i] < size`, else null |
| `{"t":"decline"}` | R → S | |
| `{"t":"ack","file","offset"}` | R → S | written and flushed up to `offset`; at least every MiB |
| `{"t":"end","file","sha256"}` | S → R | after the last data of a file: SHA-256 of all its bytes |
| `{"t":"file_ok","file"}` | R → S | hash matched, file is in place |
| `{"t":"done"}` | R → S | every file is in place |
| `{"t":"pause"}` · `{"t":"cancel"}` · `{"t":"fail","reason"}` | both | then both close; `reason` as in the website API |
| `{"t":"lan","addrs":[…],"port"}` · `{"t":"lan_on"}` · `{"t":"lan_ok"}` | see below | |

The sender sends files in order, each from `offsets[i]`, and keeps at most
8 MiB beyond the last acknowledged offset in flight. Before it continues a
file it re-reads the first `offsets[i]` bytes and compares their hash with
`prefix[i]` (sequential reading, so `content://` sources need no seeking); a
mismatch, or a size or modification time different from the offer, is
`fail source_changed`. The receiver re-hashes its partial file the same way
after a restart. A file is "done" only after `end` matched the receiver's
hash of the whole file.

### Local network

Right after the handshake one app listens on a random TCP port of its
private IPv4 addresses (10/8, 172.16/12, 192.168/16): the receiver, except a
Windows receiver of a non-Windows sender, where the sender listens (so a
Windows firewall prompt appears only between two Windows computers). It
sends `{"t":"lan","addrs":[up to 8],"port"}`. The other app connects to every
address at once (1.5 s each) and sends the frame `0x20 | transfer |
HMAC-SHA256(kLan, "levik-drop/v1 lan dialer")`; the listener checks it,
answers `0x21 | HMAC-SHA256(kLan, "levik-drop/v1 lan listener")` and closes
any other attempt. Once both checked, the sender sends `lan_on` as its last
record through the relay and every later record on the direct connection;
the receiver answers `lan_ok` as its last record through the relay and
switches too. Records keep their counters across the switch. If the direct
connection breaks, the session ends and the next one starts through the
relay (and may switch again). A VPN Kill Switch, a guest Wi-Fi or a firewall
that blocks the connection simply leaves the transfer on the relay.

## Saving

- The receiver accepts every transfer itself and picks the folder (desktop:
  Downloads by default, «Изменить»; Android: Downloads/Levik through the
  system, or a folder picked with the system picker, without storage access).
- A name from the manifest is never a path: control and bidirectional
  characters, `/ \ : * ? " < > |`, leading dots and trailing dots and spaces
  are replaced, Windows device names get a `_`, it is NFC-normalized and cut
  to 180 bytes keeping the extension.
- Existing files are never overwritten: `name (1).ext`, `name (2).ext`…
- Nothing is opened, run or unpacked. Desktop files get the system's
  "downloaded from the internet" mark (Windows Zone.Identifier, macOS
  quarantine); nothing gets an executable bit.
- Partial files (`.<name>.levikdrop`) live next to the target and are removed
  on cancel, on failure, and 24 hours after the last activity. Cancel never
  removes files that were already completed.

## Platforms

- **Android** (Direct builds only, inside Levik VPN: the profile card under
  «Мои устройства», and «Поделиться» from other apps) is ready while the app
  is visible or the VPN is connected (the event stream is open); otherwise the
  sender shows «Откройте Levik на телефоне». A transfer the user started or
  accepted continues in a `dataSync` foreground service with a progress
  notification; an offer nobody answered does not keep the app running.
  Android 15 allows such a service six hours a day: after that the transfer
  waits until the app is opened again. Received files go to Downloads/Levik
  through MediaStore (a pending entry until the file is complete, Android 10+)
  or to a folder picked with the system picker (required on Android 8–9).
  Files picked in the app stay readable after a restart; files shared from
  another app are readable only while the transfer service runs. The identity
  key is a non-exportable Android Keystore key.
- **Desktops** are ready while signed in. Files, keys and sockets stay in the
  main process; the window only asks and shows. Files can be dragged onto a
  device.
- With the VPN on, the relay connection follows the app's normal routing and
  Kill Switch.
