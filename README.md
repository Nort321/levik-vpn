# LevikVPN Monorepo

Production monorepo for LevikVPN, containing the native Android client, Next.js web cabinet and mobile BFF, Telegram bridge service, and CI/CD release automation.

## Project Structure

- `levik_vpn_android/`: Native Android application built with Kotlin and Jetpack Compose.
  - **`direct` flavor**: Independent distribution build with cryptographic GitHub Releases OTA updater and web billing links.
  - **`play` flavor**: Google Play compliance build (consumption-only, zero Play Billing dependencies, no in-app payment links/buttons, hardware-backed Play Integrity verification).
- `levik_vpn_landing/`: Next.js 16 (App Router) web application, user cabinet, legal pages, and mobile BFF.
  - Independent Levik Account model with Sign in with Google (`sub`-based), WebAuthn Passkeys, Levik ID + Argon2id passwords, and single-use Recovery Codes.
  - Mobile activation device challenge flow (`/activate`).
  - Account security management, sessions, active devices, and account deletion (`/account/delete`).
  - Zero email dependency (support tickets and recovery operate without SMTP).
- `levik_vpn_bridge/`: Python / aiogram / aiohttp integration service connecting the web cabinet, mobile activations, and Telegram identities with the Remnawave VPN core.
- `scripts/`: CI validation and release automation scripts.
- `docs/`: Architecture documentation, security model, Android signing policy, and release process.

## Architecture & Security Principles

1. **Independent Identity (Levik Account)**:
   - Accounts are identified by canonical internal UUIDs (`account_id`).
   - Telegram and Google serve as optional attached identities rather than primary keys.
   - Recovery without email via high-entropy cryptographic recovery codes and passkeys.
2. **Compile-Time Flavor Separation**:
   - `play` build excludes `REQUEST_INSTALL_PACKAGES`, updater logic, payment routes, and billing libraries at compile time.
   - `direct` build verifies signed update manifests (`SHA256withECDSA` P-256) and APK signing certificate fingerprints before triggering installation.
3. **Hardened Keystore & Cryptography**:
   - Android client signs device requests using hardware-backed Android Keystore keys (RS256/ECDSA).
   - Local VPN profiles are encrypted with Keystore keys before persisting to disk.
4. **Server Security**:
   - Production servers operate with password authentication disabled (Ed25519 SSH keys only).
   - Least-privilege database roles and isolated Docker bridge networks.

## Local Validation

### 1. Repository Policy & Linting
```bash
./scripts/ci/check-repository-policy.sh
./scripts/ci/check-action-pins.sh
./scripts/ci/check-android-release-workflow.sh
```

### 2. Web Application (Landing / Cabinet / BFF)
```bash
cd levik_vpn_landing
npm ci
npm run lint
npm run typecheck
npm test
npm run build
```

### 3. Bridge Service
```bash
cd levik_vpn_bridge
pip install -r requirements.txt pytest
PYTHONPATH=. pytest tests
```

### 4. Android Client
Use JDK 17 and Android SDK 36:
```bash
./scripts/ci/fetch-libxray.sh
cd levik_vpn_android
./gradlew verifyAllDependencyLocks
./gradlew lintDirectDebug lintPlayDebug
./gradlew assembleDirectDebug assemblePlayDebug
./gradlew testDirectDebugUnitTest testPlayDebugUnitTest
```

## Releases

Release workflows are automated via GitHub Actions using the protected `production-release` environment with manual approval gates. See [`docs/release-process.md`](docs/release-process.md) and [`docs/android-signing.md`](docs/android-signing.md).

## Licensing

Original source code in this repository is licensed under `AGPL-3.0-only`. Third-party dependencies and native libraries maintain their respective open-source licenses. See [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
