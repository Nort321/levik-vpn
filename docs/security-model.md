# Android Security Model

## Security objectives

- Protect Android signing keys, device private keys, tokens, and VPN profiles
  from accidental disclosure and unnecessary persistence.
- Authenticate protocol messages with the negotiated device key and reject
  malformed, stale, mismatched, or replayed client state where applicable.
- Keep VPN lifecycle, network, storage, UI, and native-runtime failures bounded
  and observable without logging sensitive values.
- Make every distributable artifact traceable to reviewed source, pinned inputs,
  and a verified signing identity.
- Treat the client as non-authoritative for authentication, entitlement,
  device limits, integrity, and revocation decisions.

## Repository controls

The repository is Android-only. `.gitignore` excludes the local website
worktree, and `scripts/ci/check-repository-policy.sh` rejects any tracked
`levik_vpn_landing/**` path along with credentials, sessions, databases,
backups, signing keys, native/release binaries, and build output. It also
requires the pinned libXray fetcher. A separate check rejects mutable remote
GitHub Action references.

Ignore rules do not protect a secret once it is staged, committed, copied into
an artifact, or sent to another system. Secret scanning and review remain
required before any broader repository access or publication.

## Application boundaries

- Android Keystore protects non-exportable device keys subject to platform and
  device guarantees. Code must handle invalidation and replacement explicitly.
- Sensitive local state is encrypted and minimized. Logs, crash reports,
  analytics, notifications, and screenshots must not expose tokens or profiles.
- External API input is bounded and validated. TLS is necessary but does not
  replace protocol authentication, replay controls, authorization, or response
  validation.
- `VpnService` and libXray process attacker-influenced configuration and network
  traffic. Configuration must be constrained before entering the native
  boundary, and native failures must not leave stale tunnel state presented as
  connected.
- Play Integrity results are forwarded only as part of a verified protocol.
  Client-side success is never itself an authorization decision.

Rooted or instrumented devices can observe decrypted runtime configuration. No
client-only design can make extraction impossible; short-lived server-issued
profiles, device/session revocation, and bounded authorization must be enforced
by the external service.

## External-service boundary

Website, backend, mobile BFF, bot, bridge, database, and production
infrastructure source are absent from this repository. Their behavior is an
external dependency, and their security cannot be inferred from Android client
checks or documentation. Any future source import requires an explicit scope,
ownership and licensing review, credential rotation, dependency pinning, and
its own security validation before it can be described as supported.

## Supply chain and signing

`scripts/ci/fetch-libxray.sh` downloads one versioned official asset, validates
its exact size and archive SHA-256, extracts only the expected AAR member, and
validates the AAR SHA-256 before installation. Build configuration verifies the
AAR digest again for release tasks. Third-party license and corresponding-source
obligations remain separate release gates.

Production signing values are available only to an approval-protected release
environment. Pull requests use no signing or production credentials. Temporary
key material receives restrictive permissions, is removed after use, and never
appears in commands, logs, caches, artifacts, SBOMs, or screenshots.

## Known release blockers

- Hosted CI, branch/ruleset enforcement, secret scanning, release-environment
  approvals, and attestations must be confirmed after the private GitHub
  repository is created.
- The production signing identity and independently recorded certificate digest
  must be confirmed before distribution.
- The exact libXray/Xray-core/sing notice, license-text, and corresponding-source
  bundle must be assembled and verified.
- External service readiness and authorization behavior require independent
  validation outside this repository.

These are blockers, not accepted permanent risk. This document does not claim
that the repository is currently public or that a release is approved.
