# One product: the website and the apps

The Android, Windows, macOS and Linux apps share a few services with the
website leviknet.org. This page describes what each one sends and receives,
so anyone can check that nothing more leaves the device.

## Client header

Requests to the services below carry `X-Levik-Client: <platform>/<version>`,
for example `android/1.9.0` or `windows/1.5.0`. Platforms: `android`,
`windows`, `macos`, `linux`. The header names the app build, not the device.

## Remote configuration

`GET /api/app/v1/config`, anonymous: no account, session, device or install
identifier. Only the client header is sent.

```json
{
  "ok": true,
  "refreshAfterSeconds": 900,
  "flags": { "smart_protocol": { "enabled": true, "rolloutPercent": 50 } },
  "announcements": [
    {
      "id": "uuid",
      "level": "info | warning | critical",
      "title": "≤ 80 characters",
      "body": "≤ 600 characters",
      "linkUrl": "https://leviknet.org/… or null",
      "notify": false,
      "startsAt": "ISO 8601",
      "endsAt": "ISO 8601"
    }
  ],
  "protocols": { "preferred": ["vless-reality"], "avoid": ["hysteria2"] },
  "maintenance": [
    { "nodeId": "n1…", "startsAt": "ISO 8601", "endsAt": "ISO 8601" }
  ]
}
```

- **Flags** switch features per platform and minimum version. A gradual
  rollout (`rolloutPercent` below 100) is decided on the device: the app hashes
  a random salt that it created locally and never sends together with the flag
  key. The server cannot tell which installs have a feature.
- **Announcements** are shown on the main screen until they end or the user
  closes them. With `notify`, the app also posts one system notification.
  Apps open `linkUrl` only for Levik website and Telegram addresses over
  https; any other link is dropped.
- **Protocols** come from the anonymous connection reports of the last three
  days for the caller's operator (see [connection-telemetry.md](connection-telemetry.md)).
  A protocol is ranked only with reports from at least 3 installs and 6
  sessions. Through the VPN the server sees a Levik node instead of the
  operator, so the apps ask over the physical network where the platform
  allows it, keep the advice for 6 hours, and keep the previous advice when an
  answer came through the tunnel. Automatic server selection skips avoided
  protocols and tries preferred ones first; latency still picks the server.
  If the advice would leave no server, it is ignored.

The apps ask again after `refreshAfterSeconds` (clamped to 5 minutes–24
hours), when they come to the screen, and keep the last answer for offline
starts. The endpoint is rate limited per address.

### Server maintenance

Admins plan work on a server on the app configuration page of the website: the
server's endpoint host, a start from now to 12 hours ahead and a length up to
8 hours. The configuration lists windows that start within the next 24 hours,
by [node id](#node-ids), and every open app refetches it at once through the
`config` [live event](#live-events). A withdrawn window disappears the same way.

From 15 minutes before the start until the end, the apps:

- leave the server out of automatic selection and failover, unless no other
  server remains;
- mark it in the server list with "Maintenance until HH:MM";
- move a connection on it to the fastest other server of the same kind, as soon
  as the connection has carried less than 4 KB/s for 20 seconds, and two
  minutes before the start at the latest. The app tells the user with a
  notification and does not move back when the work ends.

Android does this in the Direct build only; iOS does not take part.

## Synced settings

`GET` and `PUT /api/mobile/v1/settings`, authenticated with the app session
(the same signed requests as the rest of `/api/mobile/v1`).

Shared fields, the same meaning in every app:

| Field | Values |
| --- | --- |
| `routingMode` | `global`, `bypassRu`, `blockedOnly` |
| `automaticServer` | boolean |
| `autoReconnect` | boolean |
| `killSwitch` | boolean |
| `useDoh` | boolean |
| `antiDpiEnabled` | boolean |
| `antiDpiPackets` | `tlshello`, `N` or `N-M` (up to 3 digits), or empty; ≤ 24 characters |
| `antiDpiLength`, `antiDpiInterval` | `N` or `N-M` (up to 4 digits), or empty; ≤ 16 characters |
| `favoriteNodes` | node ids of the favourite servers, sorted, distinct, joined by `,`; at most 40; empty for none |
| `sharePresence` | boolean; absent means `true`. `false` hides every app of the account as online and deletes their status |

Split tunnelling, DNS servers, theme and autostart stay on the device. They
differ between platforms or describe the device itself.

### Node ids

A server is named by the hash of the host it connects to, so the VLESS,
Hysteria 2 and TUIC variants of one server share an id on every platform and
the id survives key rotation:

```text
host   = lowercase(endpoint host), without [] around IPv6 and without a trailing dot
nodeId = "n1" + first 20 hex characters of SHA-256("levik-node-v1|" + host)
```

`fi1.example.net` is `n1142bf5571df2373ffb23`. Engines that point at a local
port (relay, Yandex) have no node id; their favourites stay on the device.
Each app keeps its own favourites for the screen and reconciles them with the
account list: a star set or removed on this device changes the list, every
other server takes the list's state, and node ids of servers this device does
not have are kept. The first exchange after favourites became available joins
both lists. Google Play builds keep favourites on the phone.

- `PUT` takes `{ "changes": { field: value } }` with only the edited fields.
  Each field is last writer wins; the response is the whole document:
  `{ ok, settings, revision, updatedAt, updatedBy }`, where `updatedBy` is the
  platform that made the last change.
- `revision` grows with every change. Revision 0 means the account has no
  settings yet; the first app to sync uploads its current values.
- Apps send local edits after a short pause, pull on start, on sign-in and
  when they come to the screen, and apply remote values without echoing them
  back. Edits made while a request is in flight are kept and sent next.
- Sync can be turned off in each app's settings. It stops on sign-out and the
  account's stored settings are deleted with the account.

## Live events

`GET /api/mobile/v1/events` (signed like every app request, `Accept:
text/event-stream`) keeps a server-sent event stream open. Events carry no
data, only what to fetch again:

| Event | The app |
| --- | --- |
| `ready` | first event of every stream: fetch settings, remote config and account, report presence |
| `resync` | events may have been lost on the server: same as `ready` |
| `settings` | runs a settings exchange |
| `devices` | refreshes the account (a device signed in or out) |
| `presence` | refreshes the live status of the other devices, if it shows them |
| `config` | fetches the remote configuration (an announcement or switch changed) |
| `approvals` | fetches the sign-ins waiting for approval (see below) |

Unknown events are ignored. The server pings every 25 seconds and ends a
stream after 14 minutes or when the session is revoked; apps reconnect after
the `retry` delay (5 s), back off up to a minute while the server is
unreachable and drop a stream that stayed silent for 70 seconds. Desktop apps
keep the stream while signed in; Android keeps it while the app is visible or
the VPN is connected. A `401` stops the stream until a regular request
decides whether the session is gone.

## App catalog

`config/levik-categories-v1.json` groups apps and sites by meaning: banks,
payments, government services. Each app has an id, a category, a Russian and an
English name, Android package names, domains, and `suggestDirect` for apps
known to refuse working while a VPN is on. The document is signed like the
service host list (`sh config/sign.sh levik-categories-v1.json`, raise
`sequence` first) and published next to it on every mirror: the apps take the
service host list's `configUrls` and replace the file name. A copy is built
into the app; a downloaded one is used only when its signature is valid and its
sequence is higher, and it is verified again on every start. The catalog only
travels to the device: what is installed never leaves it.

Android (Direct build) checks twice a day for a newer catalog. When an
installed app with `suggestDirect` still goes through the VPN and the user has
not answered for it, the app asks while the VPN is connected: "Sber may not
work with a VPN. Let it bypass the VPN?" Yes adds the app to the apps without
VPN (starting a fresh list when all apps went through the VPN, removing it
from the list in "only selected apps" mode) and applies it at once; either
answer is remembered, closing the dialog asks again later.

## Device presence

While open, an app reports `PUT /api/mobile/v1/presence`
`{ "vpnConnected": boolean, "nodeId": string | null, "country": "DE" | null }`
on start, when its VPN state or server changes and every two minutes. The
server keeps it three minutes, without an address or history, and answers
`{ ok, shared }`; `shared: false` means the account turned presence off and
nothing was stored. `DELETE` on sign-out and quit removes it at once. Node and
country are sent only while the VPN is connected. The devices page of the
website shows "online" and the country.

## Remote

The remote lets one of the user's apps, or the devices page of the website,
ask another app of the same account to:

| Command | The app |
|---|---|
| `connect` | connects with its current settings; already connected is `done` |
| `auto_server` | turns on automatic server choice and, when connected, moves to the best server |
| `refresh_profile` | fetches the account and the tunnel profile again |
| `check_update` | looks for an update; `detail` is `available:<version>` or `up_to_date` |
| `collect_diagnostics` | creates an encrypted support note with its diagnostics and logs and answers with the note link sealed to `replyKey` |

**There is no command to disconnect.** The list is fixed in the database, in
the server and in every app: an app answers `unsupported` to anything else.

- `GET /api/mobile/v1/devices` lists the account's apps
  (`deviceId` = X-HWID, `name`, `platform`, `appVersion`, `current`, `remote`,
  `presence`) and the commands the account sent in the last 30 minutes
  (`id`, `deviceId`, `deviceName`, `command`, `state`, `detail`). `remote` is
  true for apps that ask for commands; only they are offered.
- `POST /api/mobile/v1/commands` `{ deviceId, command, replyKey? }` queues a
  command for 10 minutes. Repeating an open command returns it. `replyKey` is
  required for `collect_diagnostics` only: the SPKI (base64) of a fresh RSA key,
  2048–4096 bits, whose private half the sender keeps in memory.
- `GET /api/mobile/v1/commands` returns the open commands for the calling app,
  oldest first, and marks them delivered. Apps call it when their event stream
  opens and on every `commands` [live event](#live-events).
- `POST /api/mobile/v1/commands/result` `{ id, result, detail }` with `result`
  `done`, `failed` or `unsupported`. `detail` is a short word
  (`[A-Za-z0-9._:-]{1,80}`, for example `vpn_permission`) or, for diagnostics,
  the note link encrypted with RSA-OAEP, SHA-256 and MGF1 SHA-256 to
  `replyKey`, in base64. The server never sees the link.

States: `pending` → `delivered` → `done` / `failed` / `unsupported`, or
`expired` after 10 minutes. Every change sends `commands` to the sender's
account. Android receives commands while its event stream is open (the VPN is on
or the app is on the screen); otherwise when the app is opened next, and the
remote says so. Each command is written to the audit log.

An app shows a notification when a command connected it or collected its
diagnostics. On Android, `connect` needs the VPN permission granted once on the
phone itself; without it the answer is `failed` with `vpn_permission`. Only the
Direct build takes part; Google Play builds neither take nor send commands.

## Approving a sign-in

A new app asks the user to approve it somewhere they are already signed in,
by picking the number it shows ("number matching"). The activation code and
the QR code stay as the fallback.

The app adds `"numberMatchSupported": true` to the signed
`POST /api/mobile/v1/auth/challenge` body (only together with
`accountActivationSupported`), and `"accountHint"` if an earlier sign-in on
this device gave it one. The response then also has:

| Field | Meaning |
| --- | --- |
| `matchNumber` | 10–99, shown large on the sign-in screen |
| `approvalRequested` | `true` when the hinted account's apps (and its Telegram) were asked |
| `telegramUriComplete` | optional `https://t.me/<bot>?start=web_…`: the bot asks for the number |

The number can be picked:

- **in a signed-in app** of the hinted account. A remembered device asks its
  account at most three times in ten minutes. The `approvals` event tells
  the apps to fetch `GET /api/mobile/v1/approvals` →
  `{ ok, approvals: [{ id, deviceName, platform, platformLabel, choices: [n, n, n], expiresAt }] }`
  and answer `POST /api/mobile/v1/approvals` `{ "id": "<uuid>", "number": 37 }`
  or `"number": null` for "this is not me" → `{ ok, state: "approved" | "declined" }`;
- **in Telegram**, through `telegramUriComplete`. The bot shows the three
  numbers and "This is not me". If the remembered account has Telegram, the
  bot also writes first;
- **on the website**, through `activationUriComplete`. `/activate` shows the
  three numbers instead of a single button.

A wrong number or "This is not me" ends the attempt: `POST /auth/status` then
answers `{ "ok": true, "state": "declined" }`, and the app offers to start
again. `authenticated` responses of number-matching apps carry
`accountHint`: an opaque string the app keeps after signing out (and drops
when the user removes the account from the app), valid for 180 days.

Signing in by entering or scanning the activation code in a signed-in app
(`/activation/authorize`) needs no number: the camera proves the user is
next to the new device.

Apps that are not on the screen post one notification per request. On
Android, number matching is part of the direct build; Google Play builds sign
in with the activation code and the website as before.

## Signing in to the website from an app

`POST /api/mobile/v1/web-handoff` with `{ "target": "/dashboard…" }`, where
the target is one of `/dashboard`, `/dashboard/subscriptions`,
`/dashboard/plans`, `/dashboard/orders`, `/dashboard/devices`,
`/dashboard/support`, `/dashboard/account-security`.

The response `{ ok, url, expiresAt }` contains a one-time link
`https://leviknet.org/handoff?token=<43 characters>` that is valid for two
minutes. The apps open it only if it has exactly this form. The website asks
the user to confirm before it signs the browser in, and that browser session
does not count as a fresh sign-in for security actions (changing the
password, deleting the account). When the link cannot be made, the app opens
the same page unsigned.

On Android the "Personal cabinet" button exists only in the direct build;
Google Play builds do not link to purchases outside Play.

## Returning to the app

After payment and from emails the website links to
`https://leviknet.org/open-app?to=<screen>`, with `home`, `servers`,
`subscriptions`, `plans` or `support`.

- **Android** opens this URL directly through a verified App Link.
- **Desktop apps** register the `levik://` scheme; the page offers
  `levik://open?to=<screen>`.

The link can only choose a screen. An unknown screen opens the main one, and
nothing else in the link is used. The apps then refresh the subscription.
