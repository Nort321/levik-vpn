# Anonymous regional observations

The optional **Internet allowlist map** switch is on by default from 2.6.1, separate from
Censorship Radar. Users can disable it at any time; an explicitly saved disabled
setting is preserved on upgrade and restart. The adjacent link opens https://leviknet.com/whitelist-map
without changing the reporting setting. WorkManager performs a fresh check approximately
every 15 minutes; Android may defer background work to conserve battery.

The reporter reuses WhitelistDetector on a physical cellular non-VPN Network. A dedicated
anonymous HTTPS endpoint looks up its original IP locally and returns a signed
region token that expires at the next UTC hour. The token is held in memory only
and is discarded on network change, process restart, expiry or opt-out. Fresh
reports may use the VPN route, but never infer geography from the VPN exit IP.
New clients can fall back to the LTE CDN service endpoints selected by the signed service configuration
`/api/whitelist/service/region` and `/api/whitelist/service/report` when the website
is blocked. Both service operations bind to the same physical cellular network.
The region token records which channel issued it; delivery may retry once on the
other channel with the same fresh token. A 422 response invalidates the token.
If neither channel can issue a region token, the observation is skipped. UNKNOWN is not treated as unrestricted connectivity.

No MobileApiClient, account/session authorization, device signing, installation
ID, model, operator or diagnostic report is used. No report queue or region is
stored on disk. The server necessarily sees the IP of direct requests, but map
logs and storage exclude it; only thresholded regional states are persisted.
See the map methodology for privacy thresholds, retention and IP accuracy limits.
The protocol provides anonymous community signals, not proof of genuine clients
or absolute network anonymity from an ISP or compromised endpoint.

From 2.6.5, only the selected physical cellular internet network can contribute.
Wi-Fi, Ethernet and VPN transports are excluded before and after probing; Android
VALIDATED is not required because allowlists can block OS validation endpoints.
Reports include `transport: "cellular"`; the server rejects older reports without
this declaration. This is a client-side network check, not server attestation.
Historical closed aggregates cannot be separated by transport and remain historical.

## Subscription-independent delivery

The service channel is HTTPS through the existing LTE CDN entry, without starting
Xray, changing the active VPN, or using a subscription/account/device identity.
It has no VPN credential and cannot choose a destination. The origin accepts only
the two exact POST operations, reconstructs their bounded payload, and signs it
for the central collector using a server-only key. Old website endpoints remain
unchanged; old clients keep their existing behavior until upgraded.

The origin validates the expected cascade peer and uses the measured rightmost
CDN-forwarded address for approximate region lookup. The CDN must pass POST requests for both service endpoints.
This is community telemetry, not attestation: direct traffic through the same
cascade can forge a regional observation. This cannot grant VPN/LTE access because
there is no arbitrary proxy operation or access to the paid XHTTP handler.
See `levik_vpn_air/docs/whitelist-map.md` and its `deploy/whitelist-relay/` service.

The periodic worker no longer requires Android VALIDATED connectivity (allowlists
may block the OS probe), but the reporter still requires a physical cellular
network and exits immediately without one. UPDATE migrates existing work. Each
HTTP attempt has an 8-second budget, at most two OS calls can remain in flight,
and cancellation/opt-out/network changes prevent subsequent sends. The normal
map toggle and the Play consent gate apply to both channels. No disk queue,
GPS, SIM/operator, installation ID, or paid profile is added.

## Signed service origin

Android uses `https://f3ehyhb7vz.a.trbcdn.net` as its built-in map service origin.
Schema-1 `config/levik-config-v1.json` optionally supplies `allowlistApiOrigin`.
The entire document must pass the existing ECDSA P-256 signature and sequence
checks. Only a clean HTTPS origin (no credentials, port, path, query or fragment)
is accepted; an explicitly invalid field rejects the document. Older documents
without this field keep the built-in default. Subscription hosts and report data
cannot select a destination. The fixed region/report paths and cellular binding
are unchanged.

To move this channel again, update `allowlistApiOrigin`, increment `sequence`,
update `issuedAt`, run `sh config/sign.sh`, and publish the JSON with its `.sig`
to the existing configuration mirrors. The service configuration refresh is
checked at most twice daily and cached signed bytes are verified again on load.
Existing installed Android versions that predate this field still require an
application update; changing the signed document alone cannot teach them the field.
