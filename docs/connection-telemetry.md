# Connection quality telemetry

Levik VPN apps can send anonymous reports about how VPN connections behave:
whether a connection was established, how long it took, when and why it
dropped and how the app recovered. The reports exist to find failing servers,
protocols blocked on particular networks and regressions in new app versions.

This document is the complete contract. Android, Windows, macOS and Linux
clients send exactly these fields; the server rejects anything else.

## What is never sent

- IP addresses. The server derives the network operator (ASN) and country
  from the request address and discards the address; it is not stored with
  the reports. Abuse limits keep a keyed hash of it for at most one hour.
- Visited domains, addresses, DNS queries or traffic contents. VPN core log
  lines are reduced to fixed codes on the device; raw lines never leave it.
- Account, subscription, device or hardware identifiers, Wi-Fi names or
  phone numbers.

## Controls

- Telemetry is described on the first-run data disclosure screen and can be
  switched off at any time in Settings → Privacy → "Connection quality
  statistics". When it is off, nothing is queued or sent.
- Support reports with detailed logs are separate. They are sent only when
  the user presses "Report a problem" and are attached to the user's own
  support ticket.

## Identifiers

- `sid`: a random UUID created for every connection session (from pressing
  Connect, or an automatic start, until disconnect). It links checkpoints of
  the same session and nothing else.
- `install`: a random UUID that the app replaces every UTC day. The server
  stores only its keyed hash and uses it for rate limiting and for counting
  how many installations a problem affects.

## Transport

`POST https://leviknet.org/api/telemetry/v1/sessions` (fallback
`leviknet.com`), `Content-Type: application/json`, at most 128 KiB, no
cookies or authorization headers:

```json
{ "install": "<uuid>", "sessions": [ <session>, ... ] }
```

At most 20 sessions per request. The server answers `202` with
`{ "accepted", "stored", "rejected" }`; sessions that do not match this
contract are dropped individually. The app removes sent sessions on `202` and
on `400`, and retries later on `429` or `5xx`. Sessions are queued on the device and sent
after a successful connection, every 15 minutes while the app runs and when
it starts. A long session sends a checkpoint every 30 minutes; a later
checkpoint with the same `sid` and a higher `seq` replaces the earlier one.
Unsent data older than 7 days is discarded on the device.

When the request travels through the VPN, the server sees a Levik node
instead of the user's network. Before connecting, the app therefore asks
`GET /api/telemetry/v1/network` over the regular network. The response
contains only a signed token that encodes the ASN and country of that
network for 24 hours (all fields are `null` when the request itself came
through a Levik server):

```json
{ "token": "<opaque>", "asn": 8359, "country": "RU", "expiresAt": "..." }
```

The app attaches that token to sessions started on the same network.

## Session

```json
{
  "v": 1,
  "sid": "8a1c...-uuid",
  "seq": 0,
  "final": true,
  "ageS": 9200,
  "client": { "platform": "android", "app": "2.7.22", "os": "15", "oem": "xiaomi" },
  "net": { "type": "cellular", "token": "<from /network or null>" },
  "trigger": "user",
  "settings": { "killSwitch": true, "autoRecovery": true, "splitTunnel": false, "batteryUnrestricted": false },
  "timeline": [
    { "t": 0, "e": "attempt", "node": "Germany 1", "proto": "vless-reality", "cause": "initial" },
    { "t": 1840, "e": "connected" },
    { "t": 7203000, "e": "probe_fail", "codes": ["timeout", "tls"], "n": 3 },
    { "t": 7203100, "e": "core_log", "code": "dial_timeout", "count": 4 },
    { "t": 7203200, "e": "recovery", "action": "failover" },
    { "t": 7203300, "e": "attempt", "node": "Finland 1", "proto": "hysteria2", "cause": "failover" },
    { "t": 7204260, "e": "connected" }
  ],
  "end": { "by": "user", "code": null, "durationS": 9100 }
}
```

| Field | Values |
| --- | --- |
| `ageS` | seconds from session start until this report was sent |
| `client.platform` | `android`, `windows`, `macos`, `linux` |
| `client.app` | app version, up to 32 characters |
| `client.os` | OS major version, up to 32 characters |
| `client.oem` | Android manufacturer in lower case; omitted on desktop |
| `net.type` | `wifi`, `cellular`, `ethernet`, `other`, `unknown` |
| `trigger` | `user`, `auto_connect`, `boot`, `always_on`, `tile`, `widget`, `untrusted_wifi`, `resume`, `unknown` |
| `settings` | booleans only; `batteryUnrestricted` is Android only |
| `timeline` | at most 200 events, `t` in milliseconds from session start |
| `end` | present when `final` is `true` |

`node` is the server display name from the subscription, the same for all
users. `proto` is one of `vless-reality`, `vless-xhttp`, `vless-ws`,
`vless-grpc`, `vless-tcp`, `hysteria2`, `tuic`, `trojan`, `shadowsocks`,
`relay`, `yandex`, `other`.

## Events

| `e` | Fields | Meaning |
| --- | --- | --- |
| `attempt` | `node`, `proto`, `cause` | The app starts a tunnel. `cause`: `initial`, `reconnect`, `failover`, `network_change`, `resume`, `server_switch`, `rollback` |
| `connected` | — | The tunnel passed its end-to-end check |
| `attempt_failed` | `stage`, `code` | The attempt failed. `stage`: `profile`, `core`, `tun`, `handshake`, `verify` |
| `probe_fail` | `codes` (≤ 4), `n` | A health check through the tunnel failed; `n` consecutive failures |
| `probe_ok` | `afterFailures` | The tunnel recovered without action |
| `core_log` | `code`, `count` | VPN core reported a classified error since the last event |
| `core_exit` | `code` (exit status or null), `expected` | The VPN core process stopped |
| `net` | `type`, `state` | Underlying network `available`, `lost` or `changed` |
| `power` | `state` | `suspend`, `resume`, `doze_on`, `doze_off`, `screen_off`, `screen_on` |
| `recovery` | `action` | `reconnect_same`, `failover`, `rollback`, `lockdown`, `gave_up` |
| `pause` / `unpause` | — | The user paused the VPN |

## Codes

Codes match `^[a-z0-9_.:-]{1,48}$`. The same code means the same thing on
every platform.

| Area | Codes |
| --- | --- |
| Start | `profile_missing`, `profile_expired`, `subscription_expired`, `device_limit`, `core_unavailable`, `core_start_failed`, `tun_failed`, `permission_denied`, `helper_failed`, `config_invalid` |
| Handshake | `handshake_timeout`, `tls`, `reality_auth`, `udp_blocked`, `dns`, `refused`, `unreachable`, `reset` |
| Health checks | `timeout`, `tls`, `dns`, `refused`, `reset`, `http_<status>`, `no_vpn_network`, `other` |
| Core logs | `dial_timeout`, `dial_refused`, `reality_verify_failed`, `tls_handshake`, `quic_idle_timeout`, `quic_handshake`, `conn_reset`, `closed_pipe`, `dns_failed`, `auth_failed`, `other` |
| Session end (`end.code`) | `user`, `network_lost`, `permission_revoked`, `auth_deadline`, `subscription_expired`, `relay_terminal`, `core_exited`, `gave_up`, `replaced` (another VPN app took over), `app_update` |
| Process death (`end.by = os_killed`) | `low_memory`, `excessive_resource`, `freezer`, `crash_native`, `crash`, `anr`, `signaled`, `user_force_stop`, `unknown` |

`end.by` is `user`, `system`, `error`, `os_killed` or `unknown`.

## Retention

Raw sessions are deleted after 30 days. Daily aggregates per server,
protocol, network operator, platform and app version are kept for one year.
