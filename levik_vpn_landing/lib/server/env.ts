import { createPrivateKey } from "node:crypto";

import { z } from "zod";

const base64UrlSecret = z
  .string()
  .min(43)
  .max(64)
  .refine((value) => /^[A-Za-z0-9_-]+$/.test(value), {
    message: "must be unpadded base64url",
  })
  .refine((value) => Buffer.from(value, "base64url").byteLength === 32, {
    message: "must decode to exactly 32 bytes",
  });

const booleanString = z
  .enum(["true", "false"])
  .default("false")
  .transform((value) => value === "true");

const optionalNonEmpty = <Schema extends z.ZodType>(schema: Schema) =>
  z.preprocess(
    (value) => (value === "" ? undefined : value),
    schema.optional(),
  );

const environmentSchema = z
  .object({
    NODE_ENV: z
      .enum(["development", "test", "production"])
      .default("development"),
    APP_ORIGIN: z.string().url(),
    TELEGRAM_BOT_USERNAME: z
      .string()
      .regex(/^[A-Za-z][A-Za-z0-9_]{4,31}$/),
    BRIDGE_BASE_URL: z.string().url(),
    BRIDGE_KEY_ID: z.string().regex(/^[a-zA-Z0-9_-]{3,40}$/),
    BRIDGE_HMAC_SECRET: base64UrlSecret,
    SITE_FREE_PROXY_URL: z
      .string()
      .url()
      .default("https://levik.levafart.store:2095/v1/site-free-proxy"),
    SITE_FREE_PROXY_TOKEN: z
      .string()
      .min(43)
      .max(128)
      .regex(/^[A-Za-z0-9_-]+$/)
      .optional(),
    REMNAWAVE_STATUS_URL: z.string().url(),
    REMNAWAVE_API_TOKEN: z.string().min(32).max(512),
    SESSION_ENCRYPTION_KEY: base64UrlSecret,
    CSRF_HMAC_KEY: base64UrlSecret,
    AUDIT_HMAC_KEY: base64UrlSecret,
    MAINTENANCE_TOKEN: base64UrlSecret,
    GOOGLE_WEB_CLIENT_ID: optionalNonEmpty(
      z
        .string()
        .regex(/^[A-Za-z0-9_-]{20,200}\.apps\.googleusercontent\.com$/),
    ),
    GOOGLE_ANDROID_CLIENT_IDS: z.string().max(4_096).default(""),
    MONITOR_PROBE_SECRETS: z.string().max(16_384).default("{}"),
    ADMIN_USER_KEYS: z.string().max(16_384).default(""),
    PAYMENT_URL_ALLOWED_ORIGINS: z
      .string()
      .default("https://app.platega.io"),
    SUBSCRIPTION_URL_ALLOWED_ORIGINS: z.string().min(1),
    MOBILE_ANDROID_PACKAGE_NAME: z
      .string()
      .regex(/^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){1,9}$/)
      .default("com.leviknet.vpn"),
    MOBILE_ANDROID_CERTIFICATE_SHA256_DIGESTS: z.string().default(""),
    MOBILE_PLAY_INTEGRITY_REQUIRED: booleanString,
    MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_EMAIL: optionalNonEmpty(
      z.string().email().max(320),
    ),
    MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_PRIVATE_KEY_BASE64:
      optionalNonEmpty(
        z
          .string()
          .min(1_000)
          .max(32_768)
          .regex(/^[A-Za-z0-9_-]+$/),
      ),
    DB_HOST: z.string().min(1).max(253),
    DB_PORT: z.coerce.number().int().min(1).max(65535).default(5432),
    DB_NAME: z.string().regex(/^[a-zA-Z][a-zA-Z0-9_]{0,62}$/),
    DB_USER: z.string().regex(/^[a-zA-Z][a-zA-Z0-9_]{0,62}$/),
    DB_PASSWORD: z.string().min(24).max(1024),
    DB_SSL: booleanString,
    FEATURE_PAYMENTS_ENABLED: booleanString,
    FEATURE_DEVICE_MUTATIONS_ENABLED: booleanString,
    FEATURE_FREE_PROXY_ENABLED: booleanString,
    FEATURE_TRIAL_ENABLED: booleanString,
    FEATURE_ADMIN_UPDATES_ENABLED: booleanString,
  })
  .superRefine((value, context) => {
    const origin = new URL(value.APP_ORIGIN);
    if (
      origin.protocol !== "https:" ||
      origin.username ||
      origin.password ||
      origin.pathname !== "/" ||
      origin.search ||
      origin.hash
    ) {
      context.addIssue({
        code: "custom",
        path: ["APP_ORIGIN"],
        message: "must be an HTTPS origin without path, credentials or query",
      });
    }

    const bridge = new URL(value.BRIDGE_BASE_URL);
    if (
      bridge.protocol !== "https:" ||
      bridge.username ||
      bridge.password ||
      bridge.search ||
      bridge.hash
    ) {
      context.addIssue({
        code: "custom",
        path: ["BRIDGE_BASE_URL"],
        message: "must be a fixed HTTPS URL without credentials or query",
      });
    }

    const freeProxy = new URL(value.SITE_FREE_PROXY_URL);
    if (
      freeProxy.protocol !== "https:" ||
      freeProxy.username ||
      freeProxy.password ||
      freeProxy.search ||
      freeProxy.hash
    ) {
      context.addIssue({
        code: "custom",
        path: ["SITE_FREE_PROXY_URL"],
        message: "must be a fixed HTTPS URL without credentials or query",
      });
    }
    if (value.FEATURE_FREE_PROXY_ENABLED && !value.SITE_FREE_PROXY_TOKEN) {
      context.addIssue({
        code: "custom",
        path: ["SITE_FREE_PROXY_TOKEN"],
        message: "is required when free proxy is enabled",
      });
    }
    const remnawaveStatus = new URL(value.REMNAWAVE_STATUS_URL);
    if (
      remnawaveStatus.protocol !== "https:" ||
      remnawaveStatus.username ||
      remnawaveStatus.password ||
      remnawaveStatus.search ||
      remnawaveStatus.hash
    ) {
      context.addIssue({
        code: "custom",
        path: ["REMNAWAVE_STATUS_URL"],
        message: "must be a fixed HTTPS URL without credentials or query",
      });
    }
    const hasIntegrityEmail =
      value.MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_EMAIL !== undefined;
    const hasIntegrityPrivateKey =
      value.MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_PRIVATE_KEY_BASE64 !==
      undefined;
    if (hasIntegrityEmail !== hasIntegrityPrivateKey) {
      context.addIssue({
        code: "custom",
        path: [
          hasIntegrityEmail
            ? "MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_PRIVATE_KEY_BASE64"
            : "MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_EMAIL",
        ],
        message: "must be configured together with the Play Integrity service account",
      });
    }
    if (
      value.MOBILE_PLAY_INTEGRITY_REQUIRED &&
      (!hasIntegrityEmail || !hasIntegrityPrivateKey)
    ) {
      context.addIssue({
        code: "custom",
        path: ["MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_EMAIL"],
        message: "is required when Play Integrity is required",
      });
    }
  });

type ParsedEnvironment = z.infer<typeof environmentSchema>;

export type Environment = Omit<
  ParsedEnvironment,
  | "MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_PRIVATE_KEY_BASE64"
  | "MONITOR_PROBE_SECRETS"
  | "ADMIN_USER_KEYS"
  | "GOOGLE_ANDROID_CLIENT_IDS"
> & {
  adminUserKeys: ReadonlySet<string>;
  paymentUrlAllowedOrigins: ReadonlySet<string>;
  subscriptionUrlAllowedOrigins: ReadonlySet<string>;
  mobileAndroidCertificateDigests: ReadonlySet<string>;
  mobilePlayIntegrityServiceAccountPrivateKey?: string;
  monitorProbeSecrets: ReadonlyMap<string, string>;
  googleOAuthClientIds: ReadonlySet<string>;
};

let cachedEnvironment: Environment | undefined;

export function loadEnvironment(
  source: Record<string, string | undefined>,
): Environment {
  const parsed = environmentSchema.parse(source);
  if (
    parsed.NODE_ENV === "production" &&
    source.MOBILE_PLAY_INTEGRITY_REQUIRED === undefined
  ) {
    throw new Error(
      "MOBILE_PLAY_INTEGRITY_REQUIRED must be explicitly set in production",
    );
  }
  const parseOrigins = (value: string, name: string): ReadonlySet<string> => {
    const origins = new Set<string>();
    for (const candidate of value.split(",")) {
      const trimmed = candidate.trim();
      if (!trimmed) continue;
      const url = new URL(trimmed);
      if (
        url.protocol !== "https:" ||
        url.username ||
        url.password ||
        url.pathname !== "/" ||
        url.search ||
        url.hash
      ) {
        throw new Error(`${name} must contain only exact HTTPS origins`);
      }
      origins.add(url.origin.toLowerCase());
    }
    if (origins.size === 0) {
      throw new Error(`${name} must contain at least one HTTPS origin`);
    }
    return origins;
  };
  const certificateDigests = new Set<string>();
  for (const candidate of parsed.MOBILE_ANDROID_CERTIFICATE_SHA256_DIGESTS.split(
    ",",
  )) {
    const trimmed = candidate.trim();
    if (!trimmed) {
      continue;
    }
    const decoded = Buffer.from(trimmed, "base64url");
    if (
      !/^[A-Za-z0-9_-]{43}$/.test(trimmed) ||
      decoded.byteLength !== 32 ||
      decoded.toString("base64url") !== trimmed
    ) {
      throw new Error(
        "MOBILE_ANDROID_CERTIFICATE_SHA256_DIGESTS must contain canonical base64url SHA-256 digests",
      );
    }
    certificateDigests.add(trimmed);
  }
  const adminUserKeys = new Set<string>();
  for (const candidate of parsed.ADMIN_USER_KEYS.split(",")) {
    const trimmed = candidate.trim();
    if (!trimmed) {
      continue;
    }
    if (!/^usr_[A-Za-z0-9_-]{20,80}$/.test(trimmed)) {
      throw new Error("ADMIN_USER_KEYS contains an invalid user key");
    }
    adminUserKeys.add(trimmed);
  }
  if (parsed.FEATURE_ADMIN_UPDATES_ENABLED && adminUserKeys.size === 0) {
    throw new Error(
      "ADMIN_USER_KEYS is required when admin updates are enabled",
    );
  }
  const googleOAuthClientIds = new Set<string>();
  if (parsed.GOOGLE_WEB_CLIENT_ID) {
    googleOAuthClientIds.add(parsed.GOOGLE_WEB_CLIENT_ID);
  }
  for (const candidate of parsed.GOOGLE_ANDROID_CLIENT_IDS.split(",")) {
    const trimmed = candidate.trim();
    if (!trimmed) {
      continue;
    }
    if (!/^[A-Za-z0-9_-]{20,200}\.apps\.googleusercontent\.com$/.test(trimmed)) {
      throw new Error("GOOGLE_ANDROID_CLIENT_IDS contains an invalid client id");
    }
    googleOAuthClientIds.add(trimmed);
  }
  if (
    parsed.MOBILE_PLAY_INTEGRITY_REQUIRED &&
    certificateDigests.size === 0
  ) {
    throw new Error(
      "MOBILE_ANDROID_CERTIFICATE_SHA256_DIGESTS is required when Play Integrity is required",
    );
  }
  let mobilePlayIntegrityServiceAccountPrivateKey: string | undefined;
  if (parsed.MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_PRIVATE_KEY_BASE64) {
    const encoded =
      parsed.MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_PRIVATE_KEY_BASE64;
    const decoded = Buffer.from(encoded, "base64url");
    if (
      decoded.toString("base64url") !== encoded ||
      decoded.byteLength > 24 * 1_024
    ) {
      throw new Error(
        "MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_PRIVATE_KEY_BASE64 must be canonical base64url",
      );
    }
    mobilePlayIntegrityServiceAccountPrivateKey = decoded.toString("utf8");
    try {
      const key = createPrivateKey(
        mobilePlayIntegrityServiceAccountPrivateKey,
      );
      if (key.asymmetricKeyType !== "rsa") {
        throw new Error("must contain an RSA private key");
      }
    } catch {
      throw new Error(
        "MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_PRIVATE_KEY_BASE64 must contain a valid RSA private key",
      );
    }
  }
  const monitorProbeSecrets = new Map<string, string>();
  let rawMonitorProbeSecrets: unknown;
  try {
    rawMonitorProbeSecrets = JSON.parse(parsed.MONITOR_PROBE_SECRETS) as unknown;
  } catch {
    throw new Error("MONITOR_PROBE_SECRETS must be a JSON object");
  }
  if (
    typeof rawMonitorProbeSecrets !== "object" ||
    rawMonitorProbeSecrets === null ||
    Array.isArray(rawMonitorProbeSecrets)
  ) {
    throw new Error("MONITOR_PROBE_SECRETS must be a JSON object");
  }
  for (const [probeId, secret] of Object.entries(rawMonitorProbeSecrets)) {
    if (!/^[a-z0-9][a-z0-9_-]{2,39}$/.test(probeId)) {
      throw new Error("MONITOR_PROBE_SECRETS contains an invalid probe id");
    }
    if (
      typeof secret !== "string" ||
      !/^[A-Za-z0-9_-]{43}$/.test(secret) ||
      Buffer.from(secret, "base64url").byteLength !== 32
    ) {
      throw new Error(
        `MONITOR_PROBE_SECRETS contains an invalid secret for ${probeId}`,
      );
    }
    monitorProbeSecrets.set(probeId, secret);
  }
  const {
    ADMIN_USER_KEYS: omittedAdminUserKeys,
    GOOGLE_ANDROID_CLIENT_IDS: omittedGoogleAndroidClientIds,
    MONITOR_PROBE_SECRETS: omittedMonitorProbeSecrets,
    ...safeParsed
  } = parsed;
  void omittedAdminUserKeys;
  void omittedGoogleAndroidClientIds;
  void omittedMonitorProbeSecrets;
  delete safeParsed.MOBILE_PLAY_INTEGRITY_SERVICE_ACCOUNT_PRIVATE_KEY_BASE64;

  return Object.freeze({
    ...safeParsed,
    adminUserKeys,
    paymentUrlAllowedOrigins: parseOrigins(
      parsed.PAYMENT_URL_ALLOWED_ORIGINS,
      "PAYMENT_URL_ALLOWED_ORIGINS",
    ),
    subscriptionUrlAllowedOrigins: parseOrigins(
      parsed.SUBSCRIPTION_URL_ALLOWED_ORIGINS,
      "SUBSCRIPTION_URL_ALLOWED_ORIGINS",
    ),
    mobileAndroidCertificateDigests: certificateDigests,
    googleOAuthClientIds,
    mobilePlayIntegrityServiceAccountPrivateKey,
    monitorProbeSecrets,
  });
}

export function getEnvironment(): Environment {
  cachedEnvironment ??= loadEnvironment(process.env);
  return cachedEnvironment;
}
