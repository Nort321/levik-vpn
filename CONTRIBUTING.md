# Contributing

Changes must be narrowly scoped, reviewable, and safe for an Android
production project. Do not combine unrelated refactors with a feature, fix, or
release change.

## Repository scope

Contributions may change the Android project under `levik_vpn_android/` and the
root policy, CI-support, governance, or Android documentation files needed to
maintain it.

Do not add `levik_vpn_landing/` or any website, backend, mobile BFF, bot,
bridge, database, deployment snapshot, production configuration, credential,
session, or backup material. Those components are outside this Android-only
repository and are not made open source by being present elsewhere on a local
machine.

Before making a change:

1. Read `README.md`, `SECURITY.md`, and the relevant document under `docs/`.
2. Follow the existing Kotlin, Compose, Gradle, validation, and error-handling
   patterns.
3. Check for an existing component or utility before adding another one.
4. Keep signing material, local configuration, profiles, account state, and
   generated binaries outside Git.

## Validation

Use JDK 17, Android SDK 36, and the committed Gradle wrapper. Obtain the pinned
native input through the repository downloader, then run checks in this order:

```text
./scripts/ci/fetch-libxray.sh
cd levik_vpn_android
./gradlew lint
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

From the repository root also run:

```text
./scripts/ci/check-repository-policy.sh
./scripts/ci/check-action-pins.sh
```

If a check cannot run, explain why in the pull request. Do not hide failures,
weaken assertions, or delete tests to make a change pass.

## Change requirements

- Preserve strict typing and explicit error handling; do not suppress compiler
  or lint diagnostics without a documented reason.
- Validate external API and native-library data at the boundary.
- Keep Android UI, state, networking, cryptography, storage, and VPN lifecycle
  responsibilities separated according to existing architecture.
- Add or update tests for logic, validation, persistence, and critical edge
  cases.
- Do not add a dependency when the Android platform or existing dependency set
  already provides the capability.
- Do not commit APK/AAB/AAR files, signing files, generated source, caches, or
  local backups.
- Update architecture, release, security, signing, and third-party notices when
  a change affects those contracts.

A production release additionally requires verified libXray input, production
signing without debug fallback, certificate verification, an SBOM, complete
notices and corresponding source, checksums, provenance, and closed-track test
evidence.

## Commits and pull requests

Use Conventional Commits:

```text
type(scope): concise description
```

Examples include `fix(android): reject expired device grants` and
`test(vpn): cover service restart recovery`.

Pull requests must explain the behavior change, risk, compatibility or recovery
considerations, and checks performed. Screenshots or recordings are required
for material UI changes. Never paste secrets, personal data, production logs,
or private infrastructure details into commits, issues, or pull requests.

## Licensing

By contributing original code, you agree that it is licensed under
`AGPL-3.0-only` unless the file clearly states another license and the project
owner has approved that exception. Identify third-party material and preserve
its notices; you cannot relicense material you do not own.
