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
  ],
  "nodeLoad": { "n1…": 0.42 }
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
  If the advice would leave no server, it is ignored. Android uses it as one
  input of [Smart Auto](#smart-auto).
- **Node load** tells how busy each server is, from 0 (idle) to 1 (full), by
  [node id](#node-ids), rounded to hundredths: what the node itself reports
  (see [Live node load](#live-node-load)), else the busier of the panel's
  five-minute CPU load and its online users. A server is listed under its own address and every
  host bound to it. A server behind a shared relay (`APP_RELAYED_NODES` on the
  website) is also listed under the relay address, where the busiest server
  behind it decides, and under its own relay ports as
  [endpoint ids](#node-ids). The apps look up a server's endpoint id first and
  its node id after that. The website reads the panel at most every 30
  seconds; an unreachable panel gives an empty object. Apps that know
  [Live node load](#live-node-load) use whichever answer is newer.

### Live node load

`GET /api/app/v1/load`, anonymous like the configuration, about a kilobyte:

```json
{
  "ok": true,
  "freshSeconds": 120,
  "load": { "n1…": 0.42 },
  "full": ["n1…"],
  "quality": { "n1…": 0.85 }
}
```

- **load** is the node load as in the configuration, but measured on the node:
  a small agent on each server reports every 15 seconds the busiest of its
  uplink (against the tariff's capacity), its CPU (time the hypervisor takes
  away included) and its memory once it runs short, each averaged over a
  minute. A server behind a relay counts with every hop on its way. A node
  whose agent fell silent for 45 seconds falls back to the panel's numbers.
- **full** lists nodes that stayed at 0.95 or more for two minutes; they take
  no new automatic connections until they drop below 0.9.
- **quality** tells how each node works from the caller's operator: the median
  time of the apps' traffic checks through it over the last day (a failed
  check counts as 8 seconds), against the operator's typical node. It needs at
  least five checks from three installs per node and two such nodes; it is
  rounded to steps of 0.05 and limited to 0.7–1.6. It is `null` when the
  request came through a Levik server, since the website then cannot see the
  operator.

The answer carries an `ETag`; an app sends it back in `If-None-Match` and gets
an empty 304 while nothing changed. An app asks only before an automatic
connection (unless its answer is younger than a minute, waiting 1.5 seconds at
most), when the server list is measured, every 10 minutes while an automatic
connection runs (on Android only while the screen is on) and every 2 minutes
while its current node is full. It never asks in the background otherwise.
Quality is kept for two hours and only from answers outside the tunnel.
A website without the endpoint answers 404: the apps then use the
configuration's node load as before and ask again an hour later.

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

### Smart Auto

The automatic server on Android picks among regular servers (VLESS, Hysteria 2
and TUIC; mobile, relay and home-road servers stay separate choices) by

    score = latency × 1 / (1 − load) × history × advice × quality      (lower is better)

- **Latency** is the median of three TCP connects (or QUIC answers for UDP
  servers) started 150 ms apart, the faster of two when one went unanswered,
  so one lost packet no longer makes a server look a second slower. A TCP
  server that does not answer is down; a UDP server that does not answer
  counts as 400 ms, since obfuscated servers ignore probes.
- **Load** is the node load above, 0.35 when unknown. It weighs like a queue:
  half full doubles the score, 80 % makes it five times, and the curve stops
  at 0.95 (twenty times). A reading counts fully for two minutes, then fades
  into the unknown 0.35 until it is forgotten after 15 minutes. Nodes listed
  as **full** are left out while another server remains.
- **Quality** is the operator's factor from [Live node load](#live-node-load),
  1 when unknown.
- **History** is the phone's own record per network: ×0.8 for a protocol that
  carried traffic here within a week, ×2 after one failed check (6 hours), and
  left out after failures on two different servers (a day). A network is named
  by a salted hash of the operator code (mobile data) or the gateway and DNS
  servers (Wi-Fi); the record never leaves the phone and keeps 16 networks.
- **Advice** from the website (×0.85 preferred, ×3 avoided) counts only until
  the phone has its own experience with the protocol.

After an automatic connection to a regular server, the app downloads 64 KB
through the tunnel within 8 seconds (two rounds, two independent addresses).
Operators that throttle a protocol often pass the handshake and small requests
and freeze the connection after about 16 KB, so a ping or a status check says
nothing. If the download fails while the phone's own network reaches foreign
sites, the protocol is noted as failing and the app moves to the next rung of
the ladder: the best server of a protocol not yet tried, else the best untried
server, three moves at most until the user connects again or the network
changes. In the Anti-Block routing mode the check is skipped, because
unlisted sites go around the tunnel. Each 64 KB check (not the allowlist one)
is recorded as a `check` event in [connection telemetry](connection-telemetry.md)
when the user allows it; a failure only when the phone's own network works.
Before connecting, the app asks for the [live node load](#live-node-load). The
server list moves the automatic choice only to a server scoring at least 25%
better, and failover uses the same order.

Servers within 15 % of the best share new connections: the app picks among up
to three of them, the better ones more often, by a number that stays the same
for ten minutes on one device and differs between devices (a hash of a random
salt that never leaves the device and the current ten minutes). So the devices
that see the same free node do not all land on it at once.

A running automatic connection leaves a node that stayed full, but only when
all of these hold: the node has been full for five minutes by readings no
older than three minutes, the connection has carried less than 4 KB/s for 20
seconds (nothing the user does is cut), the last such move was at least half
an hour ago, and freshly measured, another server with room scores at least
40 % better. Android does this in the Direct build only.

The check runs again mid-session when the tunnel looks frozen: over 12
seconds at least 24 KB went out and less than half as much came back. A false
alarm costs one 64 KB download, and each one doubles the wait before the next
alarm, from 2 to at most 16 minutes, since large uploads look the same; a new
connection starts at 2 minutes again. When the
ladder changes protocol, the app says so ("VLESS Reality does not work on this
network. Switched to Hysteria 2"). The history also names, per network, the
server that last passed the check; when the phone moves to a known network,
the app reconnects straight to that server and checks it again, and a failed
check clears it. The server list shows each server's load: free below 0.4,
busy below 0.75, crowded above.

#### Allowlists

While connected on mobile data or to a mobile server, the Android app probes
the physical network for allowlists: four domestic and five foreign sites,
each with its own 6-second deadline, so one that hangs never spoils the
answer. A foreign site counts only after 32 KB of its body arrive
uncompressed: Beeline lets a handshake and a tiny answer through under
allowlists and stalls the transfer after a few kilobytes. The foreign sites
(Cloudflare, Fastly, OVH, GitHub, Wikipedia) have no caches inside Russian
operators. Allowlists are on when at least two domestic sites answer and at
most one foreign site delivers; the internet is open only when most foreign
sites (three of five) deliver; anything else is unknown and changes nothing. It probes on every network change, then every 30 seconds, every 2 minutes after
two equal answers, and every 5 minutes after two more (2 minutes at most on a
mobile server, whose traffic is limited). A changed answer, a new network, a
failing health check or a tunnel that looks frozen starts over at 30 seconds.
On Wi-Fi with a regular server the app does not probe at all, since
allowlists change nothing there. Two probes in a row, the second 6 seconds
after the first, move
the connection to a mobile server, whether the server was chosen in Auto mode
or by hand. A regular server the user picks by hand while allowlists are on
stays until the network changes or allowlists end. A regular server that
carries no traffic on mobile data under allowlists is not held against its
protocol: the probe runs at once and moves the connection instead. In Auto
mode a connection started on mobile data under allowlists goes straight to a
mobile server: a relay the user is signed in to, else an ordinary one. If the phone drops mobile data on Wi-Fi under a
server that needs it, the app moves to the regular server used before (or
Smart Auto's best) instead of waiting for mobile data to return. The power
button cancels a connection that is still being set up or reconnecting.

Relays the user is signed in to (WDTT with a VK login stored in the app,
Yandex with a usable sign-in) come first, then the ordinary mobile servers
(such as LTE over XHTTP), then the relays the user is not signed in to, then
the mobile servers that failed under this network's allowlists within the
last hour. The VK and Yandex sign-in scares many users, so a relay waits
behind the ordinary servers while the user is not signed in to it. Once the
user is, the ping cannot judge between them: an ordinary mobile server is
reached through a CDN, and the ping measures the CDN's edge, not the tunnel
behind it (Beeline in Kostroma: a fast ping, a second through the tunnel).
Within each group the mobile server that last carried traffic under this
network's allowlists goes first, then the rest by Smart Auto's score; a
mobile server that did not answer the ping is tried after those that did
instead of being left out. A relay is never pinged; its score takes 400 ms for the latency. Under allowlists the history
is per server, not per protocol, and apart from the same network without
allowlists: the operator lets some addresses through and not others. Yandex is
left out without the user's sign-in. Each mobile server must download 32 KB within 12 seconds. If
it fails to start or to carry traffic while allowlists are still on, the next
one is tried, four moves at most. When every move fails, the app returns to
the server used before and tries again after 90 seconds.

When allowlists end (two probes, at least 90 seconds after the last move), the
app returns to the server used before them unless it is gone or under
maintenance; then it takes Smart Auto's best regular server. If that regular
server then carries no traffic on the same mobile network, the probe was
wrong: the app goes back to the mobile server it left, and for 30 minutes that
network's "allowlists ended" is not believed. In Auto mode this
applies to any mobile server. With a server chosen by hand it applies only
after the app made the move itself. The user is told each time.

On Android, validated Wi-Fi or Ethernet returns an automatic mobile session to
a regular server immediately, including when a VK/Yandex relay still retains
cellular. Captive Wi-Fi does not trigger that return. A manually selected relay
stays on cellular unless the app has a pending return to a regular server, or
cellular disappears. Repeated network callbacks do not postpone the same
pending allowlist probe. A handover rechecks the preferred physical network
after server ranking, so disappearing Wi-Fi cannot move the session back to a
regular server on restricted cellular.

OFF cancels preparation, permission waits, native relay handshakes and queued
reconnects. Native VK/Yandex control reads check cancellation at every socket
poll; teardown does not wait for a native control writer. A newer ON waits for
the previous teardown, and callbacks from before OFF cannot restart it.

The Windows, macOS and Linux apps use the same score, history, ladder, freeze
check and load labels (`src/main/vpn/smartAuto.ts`), with these differences:
the automatic choice is the first entry of the server list, "Automatic", as on
Android (choosing any server turns it off, choosing it again turns it back on);
a network is named by a salted hash of its adapters' subnets and the IPv6
prefix the provider assigned; the download goes through the core's local
health proxy; a failed download counts against the protocol only when a TLS
handshake through the tunnel still works, since without one the tunnel is
simply down; TUIC stays a manual choice; the notice appears in the status
line; and the network's last winner only counts through the history.

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

`fi1.example.net` is `n1142bf5571df2373ffb23`. A relay forwards several
servers from one address on distinct ports, so for [node load](#remote-configuration)
one port of a host also has an id of the same shape; favourites and
maintenance stay by node id:

```text
endpointId = "n1" + first 20 hex characters of SHA-256("levik-endpoint-v1|" + host + "|" + port)
```

`2.27.42.184` port `21443` is `n15f491927d4394acd315d`. Engines that point at a local
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

## Server list

The apps show regular servers by country: one card per country, the
country's servers inside it, and the protocols of each server inside that.

```text
🇩🇪 Germany              country: the flag in the host remark
 ├─ Frankfurt            server: one node id, named by its label
 │   VLESS · Hysteria 2 · TUIC
 └─ Nuremberg
     VLESS · Hysteria 2
```

- **Server.** The VLESS, Hysteria 2 and TUIC variants of one endpoint host
  ([node id](#node-ids)) and country make one server, one variant per
  protocol.
- **Country.** Servers with the same country code. Mobile, LTE, relay, Yandex,
  Game and home-road («🏠») entries, and servers whose country or protocol is
  unknown, are never grouped and keep cards of their own.
- **Label.** Hosts are named `<flag> <country> · <label>`, for example
  `🇩🇪 Германия · Франкфурт`; ` | ` separates as well. Without a separator the
  label is what remains of the remark after the flag, the country's Russian or
  English name and protocol names (`VLESS`, `Reality`, `Hysteria 2`, `Hy2`,
  `TUIC`). The first variant of a server that has a label names it. A server
  without one is `Сервер N`, N being its place among the country's servers in
  subscription order, and a label that repeats inside a country gets ` 2`,
  ` 3`. Renaming a host keeps its node id: favourites and maintenance stay.
- A country with a single server looks as before: one card named after the
  country, with the label next to the protocol.
- **Choosing a country** takes its best server by the [Smart Auto](#smart-auto)
  score; a server in maintenance only when the country has no other. When one
  of the country's servers is already selected, the choice stays. Expanding the
  country picks a server, and its protocol, by hand.
- Before [maintenance](#server-maintenance) the app moves to the best other
  server of the same country first, and to another country only when the
  country has none left.
- Favourites stay per server; a country shows a star when any of its servers
  is a favourite.

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
| `commands` | fetches the commands sent to it ([Remote](#remote)) |
| `rules` | fetches the tunnel profile again and applies it ([Account rules](#account-rules)) |
| `notifications` | fetches its notifications and shows them ([Notifications](#notifications)) |
| `road` | a desktop fetches its home exit again ([Home road](#home-road)) |

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

## Account rules

"My rules everywhere": an account keeps rules that every app applies.

- **Categories** from the [app catalog](#app-catalog): `direct` or `proxy`
  (left out: like the mode). A category sends its sites one way and, on
  Android, its apps too.
- **Own sites**: up to 200 hosts, each `direct` or `proxy`; a rule covers
  subdomains, `www.` is dropped and IDN is stored as punycode. An own site wins
  over a category and the mode.

`GET /api/mobile/v1/rules` returns `{ ok, rules: { categories, domains:
[{ domain, action }] (newest first), revision }, categories: [{ id, title,
apps }] }`. `POST` takes one change and returns `{ ok, rules }`:

| Change | |
|---|---|
| `{ "op": "set_category", "category": "banks", "action": "direct" \| "proxy" \| null }` | `null` returns the category to the mode |
| `{ "op": "set_domain", "domain": "youtube.com", "action": "proxy" }` | a link or a host; the server normalises it |
| `{ "op": "remove_domain", "domain": "youtube.com" }` | |

Errors: `invalid_domain` (400), `unknown_category` (400), `too_many_rules`
(409), `rate_limited` (429). The website's cabinet has the same at
`/api/account/v1/rules` ("Мои правила"). Each change raises `revision`, is
written to the audit log and sends `rules` to the account's apps.

Where the rules apply:

- **Tunnel profile.** The server folds them into `routing.directDomains` and
  `routing.proxyDomains` (`domain:<host>`) of every profile: the account's
  entries come first, a host the user sends one way is taken out of the other
  list, and each list stays within 500 entries. Old app versions therefore
  apply them without a change. Android profiles also carry
  `routing.directApps`, the package names of the categories set to `direct`
  (only when there are any).
- **Android (Direct build).** `directApps` join the apps without VPN (they
  start such a list when every app went through the VPN; "only selected apps"
  keeps its own list). On `rules` the app fetches the profile again and, while
  connected, reconnects when the routing changed. Sharing a link to Levik VPN
  from any app asks "Through VPN / Directly" and saves the site; the profile
  has a "My rules" screen. Play builds apply only what the profile's domain
  lists carry.
- **Desktop apps** fetch the profile again on `rules` and reconnect when the
  routing changed; the tray opens the cabinet's rules page.
- **Browser extension.** For a session linked to a Levik account the gateway's
  `/session` carries `rules: { direct: [host], proxy: [host] }`, applied in the
  PAC ahead of the mode (the most specific host wins). "This site: through the
  VPN / directly" sends `POST /api/extension/v1/rules` `{ op: "set_domain" |
  "remove_domain", domain, action }`; the gateway relays it to the cabinet
  with its shared secret. Categories are changed elsewhere.
- **Routers.** The router export puts the rules ahead of the mode: sing-box
  DNS and route rules, Mihomo `DOMAIN-SUFFIX` rules.

Privacy: the rules hold only hosts the user chose and category choices; no
history is kept. Android sends nothing about the apps installed: the server
names the packages, the phone applies what is there.

## Diagnosis

"It's not the VPN, it's the internet": when something does not open, the app
says why in one sentence and offers one button.

`GET /api/app/v1/diagnose` is anonymous like the remote configuration (the
`X-Levik-Client` header, no cookies, credentials or query, 60 requests an hour
per address). Apps send it through the physical network, so the server sees
the operator rather than a Levik server; the address is neither kept nor
returned. The answer:

| Field | |
|---|---|
| `network` | `{ known, provider, country }`; `known: false` through a Levik server |
| `whitelist` | `{ status, since }` for the caller's region: `active`, `inactive`, `mixed`, `insufficient`, `stale` or `unknown` |
| `services` | `[{ slug, name, state, operator }]`: the monitor's `state` (`operational`, `degraded`, `outage`, `restricted` in Russia, `unknown`) and `operator`, how the caller's operator reached it in the website's checks of the last 15 minutes (`failing`, `working`, `unknown`; ten checks at least) |
| `protocols` | the protocol advice of the remote configuration |
| `countries` | `[{ country, online, total }]` of Levik's servers |
| `maintenance` | the maintenance windows of the remote configuration |

The app adds what the device saw: its network (none, a sign-in page, working;
Wi-Fi or mobile), the VPN state, the current server and which services did
not answer. The verdicts, first match wins:

1. No network: open network settings. A Wi-Fi sign-in page: open Wi-Fi settings.
2. Neither Levik nor any service answers: the internet is down without the
   VPN, the server is broken with it (best server).
3. The VPN fails to connect, or is connected and nothing opens: whitelists on
   a mobile network (choose a "Mobile network" server), the server is down or
   under maintenance (best server), a protocol works better on this operator
   (best server), otherwise the server is unreachable (best server).
4. A failing service is down everywhere: not the VPN, nothing to do.
5. Without the VPN: a failing service restricted in Russia, failing on the
   operator or failing for no known reason asks to connect; nothing failing
   says the internet works.
6. Through the VPN a failing service asks for another server; otherwise
   everything works and the diagnostics can go to support, with the verdict
   in the note.

Android shows the verdict on top of its diagnostics (Direct builds); desktop
apps check as soon as "Report a problem" opens and put the verdict into the
support report made within 30 minutes. Desktops check Telegram, YouTube,
Discord and GitHub through the system's routing (Chromium's network stack)
and look for a sign-in page with the operating system's own probe only while
the VPN is off. They are never on a cellular whitelist themselves, and Linux
has no network settings page to open.

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

### Family help

A family member may let the family owner help with their apps. Only the member
turns it on or off: `POST /api/mobile/v1/family` `{ "action": "help",
"allowed": true|false }`, or the switch on the family page of the website. The
family overview then carries `helpAllowed` for a member, and for each member in
the owner's list.

- `GET /api/mobile/v1/family/help` (owner) lists the members who allowed help
  (`memberId`, `name`, `lastActiveAt`, `linked`) with their apps (`ref`, `name`,
  `platform`, `appVersion`, `remote`, `online`, `vpnConnected`) and the help sent
  in the last 30 minutes (`id`, `memberId`, `deviceRef`, `command`, `state`,
  `detail`). `ref` is an opaque id: the owner never sees the device id, the
  server, the country or any traffic.
- `POST /api/mobile/v1/family/help` `{ memberId, deviceRef, command }` sends
  `refresh_profile`, `auto_server` or `check_update`. Nothing else is possible:
  not `connect`, not diagnostics, and never a disconnect. The server asks the
  bot for the member's consent on every request.

The command reaches the member's app with `via: "family"`. The app refuses any
other command from the family owner and, after a repair, tells the member what
was done. The bot also tells the owner once in Telegram when such a member has
not connected for a day after being active that week.

## Notifications

What the bot has to tell an account reaches it once, by one channel: in an
app of the account that is open, otherwise in Telegram. Today these are the
subscription reminders (from a few days before the end, once a day) and family help
(a member is not connecting).

1. The bot queues each notice in its outbox (`notification_outbox`), once per
   reminder and day. A notice of someone without a Levik account goes to
   Telegram at once.
2. Every minute the website claims the queued notices of accounts
   (`/notifications/claim` on the bot) and keeps them in
   `account_notifications`. During the account's quiet hours, by default
   23:00–09:00 Moscow time, a notice waits for their end.
3. When it is due, the website sends the `notifications`
   [live event](#live-events) if an app of the account is online and has
   asked for notifications before. If no app takes it within two minutes, or
   no app is online, the bot sends it in Telegram. An account without Telegram
   keeps it until an app asks, at most until it expires.
4. The website reports every decision to the bot (`/notifications/route`:
   `app`, `telegram` from a time, or `delivered`).

Nothing is lost while the website is down or lags: the bot sends a notice the
website did not route within 5 minutes, or held for the apps 10 minutes past
its quiet hours, in Telegram itself.

- `GET /api/mobile/v1/notifications` returns the due notices of the account,
  at most 20, oldest first: `{ id, kind, title, body, target, createdAt }`,
  with `kind` `subscription_expiring` or `family_help` and `target` one of
  `/dashboard`, `/dashboard/plans`, `/dashboard/family`. Taking them delivers
  them: no other app and no Telegram message shows them again. Apps call it
  when their event stream opens and on every `notifications` event, show each
  notice as a system notification and open `target` signed in
  ([Signing in to the website from an app](#signing-in-to-the-website-from-an-app))
  when it is tapped. Calling it also marks the app as one that is offered
  notices. Unknown kinds are shown like any other.
- `GET`/`PUT /api/account/v1/notifications` reads and changes the quiet hours:
  `{ quietHours: { enabled, from: "HH:MM", to: "HH:MM" } }`. The cabinet page
  Notifications shows them with the notices of the last 30 days and where
  each one went.

Only the Direct build of Android takes notifications; Google Play builds and
browser sessions leave them to Telegram. Signed-in desktop apps no longer
remind about the end of a subscription by themselves; they only say when it
has ended.

## Levik Home

A router with the Levik Home agent announces the account's home on its own
network. Devices of the account that were home in the last 14 days take no
slot of the subscription, and while the router's VPN is on, the apps at home
let their own VPN sleep and wake it as soon as they leave.

**Agent.** `curl -fsSL https://leviknet.org/home.sh | sh` installs
`levik-home` (Python 3, curl) on OpenWrt (opkg or apk), Keenetic with Entware
or any Linux with systemd or OpenRC; `home.sh` pins the agent's SHA-256.
`levik-home setup` pairs it; `status`, `vpn on|off|auto`, `uninstall`.

**Pairing.** `POST /api/router/v1/pair` `{ action: "create", deviceId, name }`
returns `{ token, url, qr, expiresIn: 600, interval: 5 }`. The owner opens
`url` (`/dashboard/routers?home=…`, also printed as a QR code) and confirms
the router. Polling `{ action: "poll", token }` then answers once
`{ status: "authorized", routerToken, homeId, homeKey, name }`, until then
`pending` or `expired`. An account has at most 3 homes; pairing the same
router again replaces its home. The router appears among the account's
devices as platform `router`.

**Heartbeat.** Every 2 minutes `POST /api/router/v1/heartbeat` with
`Authorization: Bearer <routerToken>` and `{ vpnActive, version }` answers
`{ home, serverTime, windows: [{ window, nonce }] }` with the nonces of the
current and the next window. A router not heard for 5 minutes is offline.
`POST /api/router/v1/unpair` forgets the home; so does removing it in the
cabinet (Routers → Дом), which also revokes the router's token.

**Beacon.** The agent answers mDNS queries for `_levik._tcp.local` on its LAN
interfaces only, never the WAN, with an instance whose TXT record is

```
v=1  h=<homeId: 22 base64url>  w=<window = floor(unix / 300)>  p=<proof>
proof = base64url(HMAC-SHA256(homeKey, "levik-home-beacon:v1|" + h + "|" + w + "|" + nonce)[0..16])
```

The nonce comes from the server and is never announced, so a recorded beacon
cannot be replayed into another window. A query from a port other than 5353
gets a direct answer (RFC 6762 §6.7); that is how desktop apps ask without
binding the mDNS port. Without a nonce for the current window (the router
lost the server) the agent stays silent.

**Apps.** `POST /api/mobile/v1/home/sighting` `{ homeId, window, proof }`
answers `{ home: { name, routerOnline, routerVpnActive } }` for a valid beacon
of the account's own home (windows ±1 are accepted) and `{ home: null }` for
anything else, including a neighbour's Levik Home. Apps report each home at
most once per window: Android while the VPN runs or the app is open (NsdManager
works under the VPN), desktops every 2 minutes with the VPN on and every 10
minutes without it.

**Slots.** Every 5 minutes the website sends the bot the devices (X-HWID) of
each account with a home that were seen at home in the last 14 days, when the
list changed and once a day anyway (`/home/slots` `{ accountId, hwids }` →
`{ users, bonus }`). For each limited subscription of the account the bot
raises Remnawave's device limit by the home devices registered on it, plus
one spare place while a home device has not joined any subscription yet, at
most 10. The bot records every limit it set; a limit changed by anything else
(a renewal, a purchase, an operator) becomes the paid limit and the home
places return within 5 minutes. Purchases of extra slots and their caps only
see the paid limit. HWIDs are never deleted: a device that stopped coming home
keeps its registration and takes a slot again.

**Sleeping at home.** With "Sleep at home" on (the default), an app whose VPN
is on and which hears its home while `routerOnline && routerVpnActive` pauses
its VPN (Android: a pause without a timer, "AT HOME" in the app and the
notification; desktops: disconnect, with the reason in the status line). It
wakes when the device leaves the network it fell asleep on, the router's VPN
stops, the setting is turned off, or the home has not answered for three
windows (15 minutes). Turning the VPN back on at home keeps it on until the
next arrival. Only the Direct build of Android has Levik Home; the Google
Play build is unchanged.

## Home road

«Дорога домой»: the owner's devices, and the family members (all their
devices or just one) the owner chooses, go out to the internet through a
computer or router at home, as if they were there. Every tariff has it; the
traffic counts against the subscription each device connects with, like any
other traffic.

**Pieces.** The relay (`levik_home_road`, private) runs next to the website
behind its Caddy at `/road/*`. Exits are the desktop apps (Windows, macOS,
Linux) and routers with the Levik Home agent 1.1.0 or later. Devices are the
apps whose tunnel profile carries «🏠» servers: the desktops and the Direct
build of Android.

**Encryption.** Every exit makes an X25519 key pair and keeps the private
half (desktops in the secure store, routers in the agent's private state).
It reports the public half (base64url, 32 bytes): desktops in `POST`, routers
as `roadKey` in every heartbeat. The devices' links carry it, so their Xray
encrypts each stream to the exit with VLESS Encryption
(`mlkem768x25519plus`: ML-KEM-768 and X25519, forward secret, 0-RTT after the
first handshake). The relay passes ciphertext and sees sizes and timing only;
the website never has the private key. An exit without a reported key gets
no links; a new key sends `rules` to everyone who may use it.

**Exit.** A desktop asks `GET /api/mobile/v1/road/exit` → `{ ok, available,
exit }` and turns itself into an exit with `POST { enabled: true, key }`
(Settings → «Пускать ко мне домой»; again whenever `exit.key` is not its own
key) or `POST { enabled: false }`; Android gets `404 not_available`. A router
gets the same `exit` as `road` in its heartbeat answer; the owner makes it an
exit in the cabinet, which refuses agents older than 1.1.0 (`router_outdated`).

```
exit = { exitId, name, mode: "direct" | "vpn", lanAccess, vlessUuid, key, controlUrl: "wss://<host>/road/x/<token>", expiresAt }
```

The exit keeps a control WebSocket at `controlUrl`. For each device
connection the relay sends `{"type":"open","id"}`; the exit opens
`controlUrl + "/" + id` and joins it to a local Xray that decrypts with the
exit's private key (VLESS on 127.0.0.1, config with the key through stdin,
errors-only log). A router uses its own Xray when it is 26.3.27 or newer,
else the official 26.7.28 release for its processor: from the relay
(`controlUrl + "/xray/26.7.28/Xray-linux-<arch>.zip"`, exit token only), then
GitHub, unpacked as it downloads and run only when the archive matches the
SHA-256 the agent pins; it is kept with the agent's files when 48 MB are free,
else in memory. `direct` leaves through the physical (WAN)
interface, past the exit's own VPN; `vpn` follows the exit's routing.
Loopback and port 25 are always closed; the home network (private,
link-local, CGNAT, multicast) and a router's own addresses only with
`lanAccess`. On macOS and Linux the
Kill Switch lets only the tunnel out, so `direct` cannot leave while it is
on and Settings say so; the Windows Kill Switch lets `xray.exe` out. Desktops
fetch the exit at start, at sign-in, on `ready`, `resync`, `devices` and
`road`, and every 6 hours; tokens last 30 days, and a fresh token alone does
not restart the exit.

**Devices.** With `X-Levik-Road: 1` on `POST /api/mobile/v1/tunnel-profile`
the profile gets one link per exit the device may use, never its own:

```
vless://<vlessUuid>@<host>:443?encryption=mlkem768x25519plus.native.0rtt.<key>&security=tls&sni=<host>&alpn=http/1.1&fp=chrome&type=ws&host=<host>&path=/road/c/<token>#🏠 <name>
```

Automatic selection and failover never pick a «🏠» server. The Play build
does not send the header. When a link appears, changes or goes, the device
gets `rules` and fetches its profile again.

**Access.** The cabinet (`/dashboard/road`, `GET`/`POST
/api/account/v1/road` with `op` `router`, `update`, `remove`, `allow`,
`deny`) lists the exits, their mode and network access, who may connect and
how much each used. A new exit lets in all of the owner's devices; family
members come from the family roster, with all their devices or one. At most
5 exits per account and 40 grants per exit.

**Relay.** It holds no account data. Every 30 seconds it fetches `GET
/api/road/v1/relay/state` (exits, and per grant and device the exit and the
subscription), refuses everyone once the list is older than 10 minutes, and
ends open streams whose access is gone. Every 60 seconds it reports bytes
per grant and device with `POST /api/road/v1/relay/usage` and a batch id,
repeated until accepted; the website counts each batch once.

**Traffic.** The website passes the bytes to the bot (`/road/traffic`, in
idempotent chunks). The bot lowers Remnawave's traffic limit of the user that
carries the subscription's traffic (the mobile part of a multi subscription)
to the paid limit minus the road bytes of the current period, never below
one byte; a new period gives them back, and purchases see the paid limit. A
subscription that is used up, expired or unknown to the bot is reported as
not allowed (`/road/status`), and the relay stops letting it through.
Unlimited subscriptions pass.

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
`/dashboard/support`, `/dashboard/account-security`, `/dashboard/rules`,
`/dashboard/family`, `/dashboard/notifications`.

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

## Ordinary service routes

Protocol 1 adds a separate cloud policy for domain masks, services, presets,
device overrides and route-local failure behavior. Its signed mobile resource
is `/api/mobile/v1/service-routes/profile`, its event is `service_routes`, and
its rollout flag starts disabled. It does not modify the legacy tunnel-profile
resource or the LTE/relay builders. Ordinary XHTTP remains eligible; LTE squad
and all whitelist-bypass endpoints are excluded by authoritative metadata.
See [service-routes.md](service-routes.md) for the wire contract, bootstrap and
application states, limits, security boundary and rollout acceptance checks.
