# Security Policy

## Supported scope

Security fixes apply to the latest maintained Android source revision and to
release lines explicitly identified as supported in release notes. Root policy,
dependency-fetch, and release-governance files are also in scope.

The website, backend, mobile BFF, bot, bridge, deployment infrastructure,
production data, local backups, and untracked binaries are not included in this
repository. References to external API behavior in Android code are contracts
consumed by the client, not proof that the corresponding service source is
present or covered by this policy.

## Reporting a vulnerability

Do not place vulnerability details in a public issue. Use the repository's
private GitHub security-advisory channel when it is enabled. Otherwise contact
the maintainer through a previously verified private channel and request a
secure reporting method before sending technical evidence.

Do not include credentials, tokens, signing keys, session material, user data,
VPN profiles, production host details, or complete production logs in the
initial report. Provide the affected Android version or source revision,
impact, prerequisites, and minimal reproduction steps. Sensitive evidence must
use the agreed private channel.

Maintainers coordinate validation, remediation, and disclosure on a
best-effort basis. No public bounty or response-time SLA is offered unless a
separate written program states otherwise.

## Credential exposure

Treat a credential pasted into chat, logs, source, an issue, a build artifact,
or broadly readable storage as compromised. Deleting the visible value is not
sufficient: revoke or rotate it, invalidate affected sessions, review access,
and remove it from retained artifacts and Git history before any publication.

Repository examples contain names and placeholders only. Signing material and
production values belong in an approved secrets system. Pull-request workflows
must not receive release signing or production credentials.

## Security-sensitive changes

Changes to Android Keystore use, request signing, profile encryption, VPN
configuration, `VpnService`, native/JNI integration, update verification, Play
Integrity handling, release signing, dependency acquisition, CI permissions,
or secret handling require explicit security and recovery analysis in the pull
request.

Client checks cannot make the device authoritative for authentication,
entitlement, revocation, or integrity decisions. A compromised device can
observe runtime VPN configuration; external services must enforce their own
authorization and lifetime controls independently.

Third-party vulnerabilities should also be reported to the relevant upstream
project when appropriate. This repository's license does not change upstream
security or disclosure policies.
