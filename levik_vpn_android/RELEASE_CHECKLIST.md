# Secure release checklist

No Android APK or AAB may be distributed until every item below is completed.

1. Reserve and verify `com.leviknet.vpn` in Google Play Console.
2. Enroll in Play App Signing. Keep the upload key outside the repository and
   provide separate Direct and Play signing values only through CI secrets or
   an untracked `local.properties`. Record the owner-approved relationship
   between the real identities; independent configuration does not itself
   guarantee update continuity.
3. Activate Play Integrity for this exact Play application and Cloud project.
   Configure the mobile BFF to verify package name, certificate digest,
   request hash, freshness, licensing, app recognition, and device integrity.
   Integrity must be enforced server-side for login and profile issuance.
4. Add the official `libXray.aar` following `app/libs/README.md`; verify its
   pinned SHA-256 in CI before every build.
5. Complete closed-track testing on physical devices for Android 8 through 16,
   including OEM battery restrictions, IPv4-only, IPv6, captive portal,
   metered network, Wi-Fi/mobile handoff, service restart, and reboot.
   Background entitlement refresh and profile revocation are now enforced via SubscriptionSyncWorker.
6. Declare the `specialUse` foreground-service type and the `vpn` subtype in
   Play Console. Supply the required video and explanation showing the
   user-initiated VPN connection.
7. Publish `https://leviknet.com/legal/privacy` with content matching actual
   behavior and verify the in-app Profile link. Complete Data safety for
   account identifiers, device metadata, diagnostics, purchase/subscription
   state, and Play Integrity signals. Do not claim that VPN traffic content is
   collected when it is not.
8. Verify backend rate limits, device-key revocation, Remnawave HWID limits,
   audit retention, session expiry, subscription ownership checks, and allowed
   subscription origins in the production environment.
   Also verify that the request sends `accountActivationSupported=true`, the
   response returns a bounded activation code and the exact HTTPS `/activate`
   URL, and the login token never appears in that URL. Confirm both
   distributions open it in a Custom Tab. A false/absent flag alone may select
   the legacy 1.9 Telegram flow.
9. Run `./gradlew verifyAllDependencyLocks`, then in order:
   `./gradlew lintDirectRelease lintPlayRelease`,
   `./gradlew assembleDirectRelease bundlePlayRelease`, then unit and
   instrumentation tests. Inspect the signed AAB with `bundletool` and upload
   it only to an internal or closed test track first.
10. For a Direct release, publish the validated Direct APK, `update.json`, and
    `update.json.sig` in the same stable GitHub release. Sign the exact raw
    manifest bytes offline using the P-256 contract in `docs/direct-ota.md` and
    verify that its certificate fingerprint matches the release APK.
11. Generate dependency/license notices, the Gradle CycloneDX JSON/XML SBOM,
    the native AAR/Go CycloneDX JSON/XML inventory, mapping file, native
    symbols, reproducible source references, and a rollback record.
12. Generate and review the root corresponding-source bundle. Confirm that its
    recorded libXray AAR digest and embedded Go/module build metadata match the
    artifact, then distribute the bundle alongside every Direct APK containing
    the native library.
13. Record the owner-approved signing migration path from
    `docs/android-signing.md`; verify update continuity on the physical Android
    8 through 16 matrix before publishing an immutable release.
14. Verify the Profile actions open exact Levik Account routes:
    `https://leviknet.com/dashboard/support` and the discoverable account
    deletion route `https://leviknet.com/account/delete`. Generic login/relink
    copy must not present Telegram as the primary identity provider.
15. Dispatch `.github/workflows/android-release.yml` from the immutable version
    tag through the `production-release` environment. Review every artifact,
    checksum, SBOM, corresponding-source bundle, and certificate before
    promoting the draft. Do not claim Direct OTA works while the repository is
    private, and never place a GitHub token in the application.

Rooted or instrumented devices can observe decrypted traffic configuration in
process memory. Hardware-backed non-exportable keys, Play Integrity,
server-side entitlement enforcement, HWID limits, and device/session revocation
reduce abuse, but no Android client can make configuration extraction
mathematically impossible. The encrypted local profile intentionally persists
until logout, uninstall, server revocation, or profile replacement;
subscription expiry is enforced separately.
