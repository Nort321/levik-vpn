import { generateKeyPairSync } from "node:crypto";

import { describe, expect, it } from "vitest";

import { loadEnvironment } from "@/lib/server/env";

const BASE_ENVIRONMENT = {
  NODE_ENV: "test",
  APP_ORIGIN: "https://leviknet.com",
  TELEGRAM_BOT_USERNAME: "levikvpnbot",
  BRIDGE_BASE_URL: "https://primary.example/cabinet/v1",
  BRIDGE_KEY_ID: "cabinet-v1",
  BRIDGE_HMAC_SECRET: "A".repeat(43),
  SITE_FREE_PROXY_URL: "https://primary.example/v1/site-free-proxy",
  REMNAWAVE_STATUS_URL: "https://primary.example/v1/remnawave-status",
  REMNAWAVE_API_TOKEN: "R".repeat(43),
  SESSION_ENCRYPTION_KEY: "B".repeat(43),
  CSRF_HMAC_KEY: "C".repeat(43),
  AUDIT_HMAC_KEY: "D".repeat(43),
  MAINTENANCE_TOKEN: "E".repeat(43),
  PAYMENT_URL_ALLOWED_ORIGINS: "https://app.platega.io",
  SUBSCRIPTION_URL_ALLOWED_ORIGINS:
    "https://subscriptions.example:2095",
  DB_HOST: "127.0.0.1",
  DB_PORT: "5432",
  DB_NAME: "leviknet",
  DB_USER: "levik_app",
  DB_PASSWORD: "a-long-build-only-database-password",
  DB_SSL: "false",
  FEATURE_PAYMENTS_ENABLED: "false",
  FEATURE_DEVICE_MUTATIONS_ENABLED: "false",
  FEATURE_FREE_PROXY_ENABLED: "false",
  FEATURE_TRIAL_ENABLED: "false",
} satisfies Record<string, string>;

const TEST_SERVICE_ACCOUNT_PRIVATE_KEY_BASE64 = Buffer.from(
  generateKeyPairSync("rsa", {
    modulusLength: 2_048,
    privateKeyEncoding: {
      type: "pkcs8",
      format: "pem",
    },
    publicKeyEncoding: {
      type: "spki",
      format: "pem",
    },
  }).privateKey,
  "utf8",
).toString("base64url");

describe("environment boundary validation", () => {
  it("loads exact HTTPS origins and keeps feature flags fail-closed", () => {
    const environment = loadEnvironment(BASE_ENVIRONMENT);
    expect(environment.paymentUrlAllowedOrigins).toEqual(
      new Set(["https://app.platega.io"]),
    );
    expect(environment.subscriptionUrlAllowedOrigins).toEqual(
      new Set(["https://subscriptions.example:2095"]),
    );
    expect(environment.FEATURE_PAYMENTS_ENABLED).toBe(false);
    expect(environment.MOBILE_ANDROID_PACKAGE_NAME).toBe(
      "com.leviknet.vpn",
    );
    expect(environment.MOBILE_PLAY_INTEGRITY_REQUIRED).toBe(false);
    expect(environment.FEATURE_ADMIN_UPDATES_ENABLED).toBe(false);
    expect(environment.adminUserKeys).toEqual(new Set());
    expect(environment).not.toHaveProperty("ADMIN_USER_KEYS");
  });

  it("enables admin updates only with a validated allowlist", () => {
    const adminUserKey = `usr_${"a".repeat(20)}`;
    const environment = loadEnvironment({
      ...BASE_ENVIRONMENT,
      FEATURE_ADMIN_UPDATES_ENABLED: "true",
      ADMIN_USER_KEYS: adminUserKey,
    });

    expect(environment.FEATURE_ADMIN_UPDATES_ENABLED).toBe(true);
    expect(environment.adminUserKeys).toEqual(new Set([adminUserKey]));
    expect(environment).not.toHaveProperty("ADMIN_USER_KEYS");
  });

  it("validates Google audiences without exposing the raw Android list", () => {
    const webClientId =
      "123456789012-abcdefghijklmnopqrstuv.apps.googleusercontent.com";
    const androidClientId =
      "987654321098-zyxwvutsrqponmlkjihgfedc.apps.googleusercontent.com";
    const environment = loadEnvironment({
      ...BASE_ENVIRONMENT,
      GOOGLE_WEB_CLIENT_ID: webClientId,
      GOOGLE_ANDROID_CLIENT_IDS: androidClientId,
    });

    expect(environment.googleOAuthClientIds).toEqual(
      new Set([webClientId, androidClientId]),
    );
    expect(environment).not.toHaveProperty("GOOGLE_ANDROID_CLIENT_IDS");
  });

  it("fails closed when admin updates lack a valid allowlist", () => {
    expect(() =>
      loadEnvironment({
        ...BASE_ENVIRONMENT,
        NODE_ENV: "production",
        MOBILE_PLAY_INTEGRITY_REQUIRED: "false",
        FEATURE_ADMIN_UPDATES_ENABLED: "true",
      }),
    ).toThrow("ADMIN_USER_KEYS is required");

    expect(() =>
      loadEnvironment({
        ...BASE_ENVIRONMENT,
        FEATURE_ADMIN_UPDATES_ENABLED: "true",
        ADMIN_USER_KEYS: "invalid-user-key",
      }),
    ).toThrow("ADMIN_USER_KEYS contains an invalid user key");
  });

  it("treats blank optional Play credentials as absent", () => {
    const environment = loadEnvironment({
      ...BASE_ENVIRONMENT,
      MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_EMAIL: "",
      MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_PRIVATE_KEY_BASE64: "",
    });
    expect(
      environment.MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_EMAIL,
    ).toBeUndefined();
    expect(
      environment.mobilePlayIntegrityServiceAccountPrivateKey,
    ).toBeUndefined();
  });

  it("requires an explicit integrity policy in production", () => {
    expect(() =>
      loadEnvironment({
        ...BASE_ENVIRONMENT,
        NODE_ENV: "production",
      }),
    ).toThrow("must be explicitly set in production");

    expect(
      loadEnvironment({
        ...BASE_ENVIRONMENT,
        NODE_ENV: "production",
        MOBILE_PLAY_INTEGRITY_REQUIRED: "false",
      }).MOBILE_PLAY_INTEGRITY_REQUIRED,
    ).toBe(false);
  });

  it("rejects a bridge URL containing credentials", () => {
    expect(() =>
      loadEnvironment({
        ...BASE_ENVIRONMENT,
        BRIDGE_BASE_URL:
          "https://user:password@primary.example/cabinet/v1",
      }),
    ).toThrow();
  });

  it("rejects non-HTTPS payment origins", () => {
    expect(() =>
      loadEnvironment({
        ...BASE_ENVIRONMENT,
        PAYMENT_URL_ALLOWED_ORIGINS: "http://app.platega.io",
      }),
    ).toThrow();
  });

  it("rejects an allowlisted origin with a path", () => {
    expect(() =>
      loadEnvironment({
        ...BASE_ENVIRONMENT,
        SUBSCRIPTION_URL_ALLOWED_ORIGINS:
          "https://subscriptions.example/private",
      }),
    ).toThrow();
  });

  it("rejects secrets shorter than 256 bits", () => {
    expect(() =>
      loadEnvironment({
        ...BASE_ENVIRONMENT,
        CSRF_HMAC_KEY: "short",
      }),
    ).toThrow();
  });

  it("rejects a maintenance token that is not 256 bits", () => {
    expect(() =>
      loadEnvironment({
        ...BASE_ENVIRONMENT,
        MAINTENANCE_TOKEN: "short",
      }),
    ).toThrow();
  });

  it("parses per-probe monitor secrets without exposing the raw JSON", () => {
    const probeSecret = Buffer.alloc(32, 9).toString("base64url");
    const environment = loadEnvironment({
      ...BASE_ENVIRONMENT,
      MONITOR_PROBE_SECRETS: JSON.stringify({ "probe-ru": probeSecret }),
    });

    expect(environment.monitorProbeSecrets.get("probe-ru")).toBe(probeSecret);
    expect(environment).not.toHaveProperty("MONITOR_PROBE_SECRETS");
  });

  it("rejects malformed monitor probe secrets", () => {
    expect(() => loadEnvironment({
      ...BASE_ENVIRONMENT,
      MONITOR_PROBE_SECRETS: JSON.stringify({ "probe-ru": "short" }),
    })).toThrow("invalid secret");
  });

  it("requires a site proxy token when public proxy issuance is enabled", () => {
    expect(() =>
      loadEnvironment({
        ...BASE_ENVIRONMENT,
        FEATURE_FREE_PROXY_ENABLED: "true",
      }),
    ).toThrow();

    expect(
      loadEnvironment({
        ...BASE_ENVIRONMENT,
        FEATURE_FREE_PROXY_ENABLED: "true",
        SITE_FREE_PROXY_TOKEN: "F".repeat(43),
      }).FEATURE_FREE_PROXY_ENABLED,
    ).toBe(true);
  });

  it("requires Play signing and Google credentials in integrity mode", () => {
    expect(() =>
      loadEnvironment({
        ...BASE_ENVIRONMENT,
        MOBILE_PLAY_INTEGRITY_REQUIRED: "true",
      }),
    ).toThrow();

    const certificateDigest = Buffer.alloc(32, 15).toString("base64url");
    const environment = loadEnvironment({
      ...BASE_ENVIRONMENT,
      MOBILE_PLAY_INTEGRITY_REQUIRED: "true",
      MOBILE_ANDROID_CERTIFICATE_SHA256_DIGESTS: certificateDigest,
      MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_EMAIL:
        "integrity@example-project.iam.gserviceaccount.com",
      MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_PRIVATE_KEY_BASE64:
        TEST_SERVICE_ACCOUNT_PRIVATE_KEY_BASE64,
    });
    expect(environment.mobileAndroidCertificateDigests).toEqual(
      new Set([certificateDigest]),
    );
    expect(
      environment.mobilePlayIntegrityServiceAccountPrivateKey,
    ).toContain("BEGIN PRIVATE KEY");
    expect(environment).not.toHaveProperty(
      "MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_PRIVATE_KEY_BASE64",
    );
  });

  it("rejects incomplete or invalid Play Integrity credentials", () => {
    expect(() =>
      loadEnvironment({
        ...BASE_ENVIRONMENT,
        MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_EMAIL:
          "integrity@example-project.iam.gserviceaccount.com",
      }),
    ).toThrow("must be configured together");

    expect(() =>
      loadEnvironment({
        ...BASE_ENVIRONMENT,
        MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_EMAIL:
          "integrity@example-project.iam.gserviceaccount.com",
        MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_PRIVATE_KEY_BASE64:
          Buffer.from("x".repeat(1_000), "utf8").toString("base64url"),
      }),
    ).toThrow("must contain a valid RSA private key");
  });
});
