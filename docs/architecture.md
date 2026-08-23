# Android Architecture

## Repository boundary

This repository contains one product component: the Android client under
`levik_vpn_android/`. Root files provide governance, release documentation, and
CI support for that client.

The local `levik_vpn_landing/` worktree is explicitly ignored. Website,
backend, mobile BFF, bot, bridge, database, deployment infrastructure, and
production state are not repository components. Android API models and endpoint
paths describe an external client contract only; they do not supply or license
the remote implementation.

## Client responsibilities

The Android application separates these responsibilities:

- Jetpack Compose presents UI and observes application state;
- networking code serializes bounded requests and validates responses from
  externally operated APIs;
- Android Keystore keys identify the installation and sign protocol messages;
- sensitive tokens and profiles are encrypted before local persistence;
- Android `VpnService` owns tunnel lifecycle and invokes the pinned libXray
  native runtime;
- background work refreshes client state without becoming authoritative for
  subscription, device, or revocation decisions.

UI, network, cryptographic, storage, and VPN lifecycle code must remain
separate enough to test their failure behavior independently.

## Trust boundaries

- The Android device is not a trusted authorization authority. Rooted,
  instrumented, or compromised devices can observe application memory and VPN
  configuration.
- External API responses are untrusted until authenticated where the protocol
  supports it, bounded, parsed, and schema-validated.
- Play Integrity and hardware-backed keys are risk signals and key-protection
  mechanisms; neither proves that arbitrary client state is truthful.
- VPN profiles are usable secrets. Store the minimum required data, encrypt it
  at rest, avoid logs and screenshots, and erase it when the application
  lifecycle requires removal.
- The app must handle expired sessions, revoked devices, unavailable services,
  malformed responses, network changes, process death, and tunnel restart
  without silently broadening access.

External services remain responsible for authentication, authorization,
entitlement, replay protection, profile lifetime, and revocation. Those
services are outside this repository and require their own source, tests, and
security policy.

## Native dependency boundary

`libXray.aar` is a third-party release input, not repository source. It is
excluded from Git and installed by `scripts/ci/fetch-libxray.sh` only after the
official archive size, archive SHA-256, expected archive member, and extracted
AAR SHA-256 are verified. Gradle independently verifies the expected AAR digest
for release tasks.

The binary incorporates additional upstream components with their own license
and corresponding-source obligations. See `THIRD_PARTY_NOTICES.md`; a matching
digest alone does not complete distribution compliance.

## Signing and release boundary

Private signing keys are release inputs and never repository content. Release
tasks must fail closed when production signing data is missing and must never
fall back to debug signing. Every distributed artifact must be tied to a
reviewed source revision, verified signer certificate, dependency inventory,
notices, checksums, and provenance.
