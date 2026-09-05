# Anonymous regional observations

The optional **Internet allowlist map** switch is off by default, separate from
Censorship Radar. The adjacent link opens https://leviknet.com/whitelist-map
without opting the user in. WorkManager performs a fresh check approximately
every 15 minutes; Android may defer background work to conserve battery.

The reporter reuses WhitelistDetector on a physical non-VPN Network. A dedicated
anonymous HTTPS endpoint looks up its original IP locally and returns a signed
region token that expires at the next UTC hour. The token is held in memory only
and is discarded on network change, process restart, expiry or opt-out. Fresh
reports may use the VPN route, but never infer geography from the VPN exit IP.
If the direct region endpoint is blocked and there is no valid cached token,
the report is skipped. UNKNOWN is not treated as unrestricted connectivity.

No MobileApiClient, account/session authorization, device signing, installation
ID, model, operator or diagnostic report is used. No report queue or region is
stored on disk. The server necessarily sees the IP of direct requests, but map
logs and storage exclude it; only thresholded regional states are persisted.
See the map methodology for privacy thresholds, retention and IP accuracy limits.
The protocol provides anonymous community signals, not proof of genuine clients
or absolute network anonymity from an ISP or compromised endpoint.
