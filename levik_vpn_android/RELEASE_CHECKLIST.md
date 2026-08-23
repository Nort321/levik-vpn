# Secure release checklist

No Android APK or AAB may be distributed until every item below is completed.

1. Reserve and verify `com.leviknet.vpn` in Google Play Console.
2. Enroll in Play App Signing. Keep the upload key outside the repository and
   provide signing values only through CI secrets or an untracked
   `local.properties`.
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
9. Run, in order: `./gradlew lintDirectRelease lintPlayRelease`,
   `./gradlew assembleDirectRelease bundlePlayRelease`, then unit and
   instrumentation tests. Inspect the signed AAB with `bundletool` and upload
   it only to an internal or closed test track first.
10. Generate dependency/license notices, an SBOM, mapping file, native symbols,
    reproducible source references, and a rollback record for the release.

Rooted or instrumented devices can observe decrypted traffic configuration in
process memory. Hardware-backed non-exportable keys, Play Integrity,
server-side entitlement enforcement, HWID limits, and device/session revocation
reduce abuse, but no Android client can make configuration extraction
mathematically impossible. The encrypted local profile intentionally persists
until logout, uninstall, server revocation, or profile replacement;
subscription expiry is enforced separately.
