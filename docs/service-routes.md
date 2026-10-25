# Ordinary VPN service routes, protocol 1

Implementation contract for the website and Android/Windows/macOS/Linux clients.
No deployment or OTA publication is part of this change.

## Protected boundary

Only explicitly classified ordinary endpoints can use the new policy. The
read-only Remnawave registry joins published hosts to their inbound/profile and
node UUID. The ordinary squads are the bridge's existing `Levik Runtime Xray`
and `Levik Runtime Hysteria`. LTE and every other squad are excluded; an inbound
or address/port shared with a protected role is denied conservatively. Missing
LTE metadata makes discovery unavailable rather than authorizing by omission.

Ordinary XHTTP is eligible. XHTTP belonging to the LTE squad, the Levik relays,
Yandex/VK/Telemost and every other whitelist-bypass mode retain their existing
profiles, routing, DNS, failover and lifecycle. No protocol-name heuristic can
grant access. Unknown or ambiguous endpoints are not enrolled. Mixed
subscriptions keep their original source unchanged.

The existing `/api/mobile/v1/tunnel-profile` endpoint and its builders are not
modified. Advanced policy is fetched separately after an endpoint is selected.
Its registry is intersected with the subscription actually issued to that
device. This fetch never automatically evicts a device slot. TUIC identity can
be derived from the existing mapping only when unambiguously ordinary; this
does not imply that every client transport supports multi-route operation.

## User policy

Rules specify a domain/service/category/preset, route, failure policy and scope.
Routes are `default`, `direct`, `main`, `country(countryCode)`, `exit(exitId)`.
Failure policies are `block`, `direct`, `main`, `country(countryCode)`; a direct
fallback always requires an explicit saved choice.

`example.com` retains the legacy apex-and-subdomains meaning. `*.example.com`
means subdomains at any depth and excludes the apex. `*.ru` and `*.рф` are valid.
An exact match is separate. Arbitrary regex, embedded stars and a bare `*` are
not accepted. The server emits anchored RE2-compatible expressions for wildcard
rules; the literal `*` is never passed to the core as a suffix.

The Russian preset is a versioned domain bundle: `.ru`, `.рф`, maintained
Russian-service domains and optional `.su`. It does not enable GeoIP bypass or
silently change the user's routing mode. Banks/government presets reuse the
existing catalogue. Disabling a preset preserves manual rules.

Scope is account-wide or a bounded list of device IDs. A selected-device write
replaces only their values for the same canonical subject. An all-device write
replaces that subject's overrides and becomes the default for future devices.
Other, more specific subjects remain. Resetting a device reveals the account
value; explicitly choosing `default` is a distinct action.

After same-subject inheritance, ordering is: existing application prohibitions
and exclusions, explicit domains (most specific first), services, categories,
presets, base routing. A manual `*.ru` is an explicit domain rule; the Russian
preset has lower precedence than explicit service assignments. Existing legacy
own domains and categories are included at their original precedence when the
new policy is active. New policy storage is capped at 200 records including
device overrides, leaving space within the 500-rule wire cap for legacy rules.

## Cloud resources and persistence

- Account `GET/POST /api/account/v1/service-routes`: existing account cookie,
  origin/CSRF checks for writes, rate limits and exact schema validation.
- Signed mobile `GET/POST /api/mobile/v1/service-routes`: same operations and
  account ownership, with current device taken from its signed session.
- Signed `POST /api/mobile/v1/service-routes/profile`:
  `{ subscriptionId, endpoint: { address, port, protocol } }`.
  Returns `{ ok:true, applicable:true, profile }` for an authorized ordinary
  endpoint, or `applicable:false, reason:"protected_or_unknown"` without
  fetching subscription credentials for a protected/unknown endpoint.
- Signed `POST /api/mobile/v1/service-routes/status`: acknowledged revision,
  fingerprint, aggregate state and bounded per-rule exit/state reports. Reports
  refer only to an exact profile issued to that device; no browsing history.

Wire `profile` contains `version:1`, account `revision`, effective `fingerprint`,
ordered compiled `rules` and ordinary `exits`. `exitId` is a stable panel node
identity, independent of credentials/address/protocol. No credentials are in
the advanced-policy response. Cached policy never extends subscription access.

Migration `029_service_routes.sql` creates separate policy, idempotency,
issued-target and status tables plus a profile-issuance sequence. It does not
rewrite `account_rules`, existing subscription data, or Remnawave. Runtime
grants are updated by the existing migration runner.

Mutations are point operations with `expectedRevision` and an idempotency key.
Committed retries remain recoverable when discovery is unavailable. Account
updates publish `service_routes`, never the legacy `rules` reload event.

The effective fingerprint excludes the account revision, so an unrelated
device edit does not restart a working tunnel. Clients acknowledge a newer
revision with an unchanged fingerprint without replacing the configuration.
Target issuance is ordered by a database generation reserved before upstream
fetches, preventing delayed same-revision responses from replacing newer
metadata. Status insertion rechecks the current target atomically. Last applied
revision/fingerprint remain separate from a failed newer attempt.

The web-to-app handoff still accepts only fixed cabinet targets. For the rules
page, the server derives its device hint from the authenticated source session;
the editor checks it against the account's device list.

## Runtime and failures

The system tunnel is shared, while each route has its own decision state.
Same-country alternatives are attempted before the explicit reserve. Reserves
are finite and cannot cycle back to the same failed candidate. A pinned exit
can change ordinary protocol without changing exit identity, only where the
client supports the transport. No implicit direct or cross-country fallback.

The first implementation activates multi-route operation with native Xray
outbounds. A selected ordinary TUIC connection reports `unsupported_transport`
and keeps its legacy behavior; the registry still recognizes it as ordinary,
not LTE. On macOS the official core cannot match process filters, so a new
policy with nonempty only/bypass process selection reports
`app_filter_unsupported` without changing that legacy configuration. Windows
and Linux scope new data rules to included processes in only-apps mode. DNS
requests resolved by an OS service may lack the originating process; domain
DNS contexts are therefore device-wide, not independently per browser.

Strict service balancers initially select an existing local block outbound.
The ordinary main balancer initially selects the user's ordinary endpoint, so
a failure of the new control channel does not itself disable ordinary internet.
Health and sticky selections are maintained separately from core/TUN health.
Only failed routes are blocked; a failed main exit does not stop working
additional countries. Existing global Kill Switch and OS restrictions remain
separate. Open TCP/QUIC flows are not promised seamless migration.

Core API mutation must use private authenticated IPC. Android uses app-private
Unix sockets; desktop service mode uses authenticated loopback API/probe
ingresses. API/probe credentials are per-session and must not be logged or
exposed to the renderer. Legacy protected-mode API configuration remains as-is.

Route DNS uses the same decision context and bounded fallback. Explicit rules
cannot silently fall through to another route's DNS answer. Endpoint bootstrap
resolution remains independent. Preserve configured resolvers where supported;
do not silently replace them with a different provider. IPv4/IPv6/QUIC and
resolver failure are separate acceptance scenarios. ECH/custom app DNS may
limit classification; no global DoH/ECH disabling is introduced.

`default` re-enters the existing base routing instead of becoming an alias for
`main`. Application exclusions are explained, not silently edited. Game keeps
its existing semantics. Home must not suspend the local ordinary tunnel when
the router does not enforce active service policy.

## Application and bootstrap states

Saved, issued, applied and reachable are separate facts. Per-device states are
waiting, pending, applied, unavailable, unsupported, suspended, error or stale.
Per-route reports include active/fallback/blocked/unavailable and actual exit.
The website labels old reports as old reports, not proof of the current save.

A client with a last working ordinary policy keeps it if the new fetch fails.
A failed replacement restores the previous working configuration. An explicit
empty policy is the only intentional removal. Before first verified enrollment,
an unavailable new resource preserves legacy/protected connectivity and shows
pending/error rather than claiming the saved rule is applied. This bootstrap
case provides no new route guarantee before the first applied receipt.

Offline edits are drafts and must be rebased against the expected revision.
Changing to LTE/relay suspends the new policy without changing the bypass.
Returning to ordinary VPN resumes the last compatible policy.

Android's durable policy cache retains up to 100 subscriptions for the current
owner. It never evicts a previously applied strict policy to make room for a
different subscription; a defensive capacity failure is an explicit error,
not an applied receipt. Signing out or changing owner invalidates pending
operations and cached owner context.

## Rollout and verification

The existing remote feature flag `service_routes` is seeded disabled with a
zero-percent rollout. New enrollment is gated by that flag; an already applied
strict policy must not be silently forgotten if rollout is disabled later.
Deploying the backend, applying the migration, enabling selected test clients,
publishing apps and OTA updates are separate later operations.

Local checks include server/UI tests, an isolated PostgreSQL/WASM migration and
transaction suite, server-to-desktop wire compatibility, platform type/build
checks, native helper checks, browser desktop/mobile layout, and actual pinned
core tests with loopback traffic. The latter must demonstrate route A failing
while route B keeps exchanging data in the same process, API authentication,
and protected/no-policy configuration preservation.

Before broader rollout also verify actual Android IPC on an Android runtime,
platform lifecycle/restore behavior, normal-node egress country (including
server-side destination detours), DNS failures and current panel discovery.
No failure injection or modifications on LTE/relay servers are authorized by
this implementation task.

Optional isolated database check (test runtime outside app dependencies):

```sh
LEVIK_TEST_PGLITE=/absolute/path/to/pglite/dist/index.js npm test
```

The embedded runtime tests real PostgreSQL SQL/transactions in memory; it does
not establish production panel availability or multi-process performance.

### Local verification recorded on 2026-10-25

| Package | Executed checks |
| --- | --- |
| Website | 669 tests passed, 49 environment-dependent tests skipped; full lint, typecheck and production build passed; new PostgreSQL suite ran using the isolated runtime |
| Android | Direct 534 and Play 481 unit tests passed; both lints had zero errors; Play debug APK/test APK assembled with relay exclusion checks |
| Windows | 530 tests passed, 7 skipped; lint and build passed |
| macOS | 270 tests passed; lint, build and Swift helper self-tests passed |
| Linux | 277 TypeScript tests passed; Python helper: 23 passed, 4 skipped (27 total); lint and build passed |

The website was checked in Chrome at desktop and mobile widths; no overflow,
console errors or account writes occurred in the guarded local preview.
Actual pinned Xray tests covered independent route failure, authenticated
control/probes, DNS wire responses and default-loopback behavior in all three
base modes. The Android arm64 emulator additionally verified the packaged
libXray Unix socket, gRPC override, retired-controller rejection and a
certificate-validated TLS probe over the wrapped LocalSocket (two tests).

These checks are not end-to-end acceptance of a physical phone, Windows/Linux
TUN/process matching, or current production egress rules. The feature remains
disabled until a separate controlled rollout; source, migration and app
publication are not substitutes for those canary checks.
