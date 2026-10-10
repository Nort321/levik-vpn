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
  "protocols": { "preferred": ["vless-reality"], "avoid": ["hysteria2"] }
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

Split tunnelling, DNS servers, theme, autostart and favourite servers stay on
the device. They differ between platforms or describe the device itself.

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
