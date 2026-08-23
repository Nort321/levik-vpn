# LevikVPN for Android

This repository is scoped to the LevikVPN Android client. It is being prepared
with a private-first workflow so that source, release inputs, and licensing can
be reviewed before any later public-source decision. This document does not
assert that the repository is currently public or that an Android binary is
ready for distribution.

Any GitHub remote must be created with **Private** visibility and that setting
must be verified before the first push. Changing visibility later requires a
separate owner decision after security, licensing, and release-readiness review.

## Included scope

- `levik_vpn_android/` contains the Kotlin/Jetpack Compose application,
  Android `VpnService` integration, tests, Gradle wrapper, and Android release
  documentation.
- `scripts/ci/` contains repository policy checks and the fail-closed downloader
  for the pinned libXray Android artifact.
- `docs/` contains Android architecture, security, release, and signing policy.

The Android application communicates with externally operated APIs. API paths,
request models, and client-side validation in this source tree are client
contracts only; they are not a server implementation.

## Explicitly excluded

The website worktree `levik_vpn_landing/` is ignored and must never be tracked
by this repository. Website, backend, mobile BFF, bot, bridge, database,
deployment, production configuration, and production state are outside this
repository's scope. Their local presence does not make them repository content,
an open-source component, or part of an Android release.

Also excluded are `.secure-backups/`, Telegram or account sessions, signing
keys, environment files, credentials, databases, APK/AAB/AAR artifacts, build
output, dependency caches, and IDE state. Ignore rules are only a guardrail;
sensitive material still requires access-controlled storage and credential
rotation after suspected exposure.

## Local validation

Use JDK 17 and Android SDK 36. Fetch and verify the pinned native dependency
before building:

```text
./scripts/ci/fetch-libxray.sh
cd levik_vpn_android
./gradlew lintDirectDebug lintPlayDebug
./gradlew assembleDirectDebug assemblePlayDebug
./gradlew testDirectDebugUnitTest testPlayDebugUnitTest
```

The downloader verifies both the official release archive and the extracted
AAR against recorded SHA-256 values. The AAR remains excluded from Git.

Repository-level checks are:

```text
./scripts/ci/check-repository-policy.sh
./scripts/ci/check-action-pins.sh
```

Do not use production credentials for local or pull-request validation.

## Releases

A debug APK is not a release. A distributable build additionally requires the
production signing identity, verified certificate digest, release lint and
tests, an SBOM, complete third-party notices and corresponding-source closure,
artifact checksums, and provenance. See `docs/release-process.md` and
`docs/android-signing.md`.

## Licensing

Original source code included in this repository is licensed under
`AGPL-3.0-only` unless a file states otherwise. This does not relicense
third-party code, dependencies, generated artifacts, trademarks, excluded local
worktrees, backups, or services whose source is not included. See `LICENSE` and
`THIRD_PARTY_NOTICES.md`.
