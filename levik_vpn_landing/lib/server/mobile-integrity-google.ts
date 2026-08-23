import "server-only";

import {
  createPrivateKey,
  sign,
  type KeyObject,
} from "node:crypto";

import { z } from "zod";

import type {
  MobileIntegrityVerifier,
  MobileIntegrityVerdict,
} from "@/lib/server/mobile-integrity";

const googleAccessTokenSchema = z
  .object({
    access_token: z.string().min(20).max(8_192),
    expires_in: z.number().int().min(60).max(86_400),
    token_type: z.string().min(1).max(40),
  })
  .passthrough();

const googleIntegrityVerdictSchema = z
  .object({
    tokenPayloadExternal: z
      .object({
        requestDetails: z
          .object({
            requestPackageName: z.string().min(1).max(255),
            requestHash: z.string().min(1).max(500),
            timestampMillis: z.union([
              z.string().regex(/^[1-9][0-9]{12}$/),
              z.number().int().safe().positive(),
            ]),
          })
          .passthrough(),
        appIntegrity: z
          .object({
            appRecognitionVerdict: z.string().min(1).max(80),
            packageName: z.string().min(1).max(255).optional(),
            certificateSha256Digest: z
              .array(z.string().regex(/^[A-Za-z0-9_-]{43}$/))
              .max(10)
              .default([]),
          })
          .passthrough(),
        accountDetails: z
          .object({
            appLicensingVerdict: z.string().min(1).max(80),
          })
          .passthrough(),
        deviceIntegrity: z
          .object({
            deviceRecognitionVerdict: z
              .array(z.string().min(1).max(80))
              .max(10)
              .default([]),
          })
          .passthrough(),
      })
      .passthrough(),
  })
  .passthrough();

type FetchImplementation = typeof fetch;

export class GooglePlayIntegrityVerifier implements MobileIntegrityVerifier {
  private readonly privateKey: KeyObject;
  private cachedAccessToken:
    | { value: string; refreshAfterMilliseconds: number }
    | undefined;
  private pendingAccessToken: Promise<string> | undefined;

  constructor(
    private readonly serviceAccountEmail: string,
    privateKeyPem: string,
    private readonly fetchImplementation: FetchImplementation = fetch,
    private readonly now: () => number = Date.now,
  ) {
    this.privateKey = createPrivateKey(privateKeyPem);
    if (this.privateKey.asymmetricKeyType !== "rsa") {
      throw new Error("Play Integrity service account key must be RSA");
    }
  }

  async verify(input: {
    integrityToken: string;
    expectedRequestHash: string;
    expectedPackageName: string;
  }): Promise<MobileIntegrityVerdict> {
    const accessToken = await this.accessToken();
    const endpoint =
      `https://playintegrity.googleapis.com/v1/` +
      `${encodeURIComponent(input.expectedPackageName)}:decodeIntegrityToken`;
    const response = await this.fetchImplementation(endpoint, {
      method: "POST",
      redirect: "error",
      cache: "no-store",
      signal: AbortSignal.timeout(PLAY_API_TIMEOUT_MILLISECONDS),
      headers: {
        Accept: JSON_MEDIA_TYPE,
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": JSON_MEDIA_TYPE,
      },
      body: JSON.stringify({
        integrity_token: input.integrityToken,
      }),
    });
    if (!response.ok) {
      if (response.status === 400) {
        throw new GooglePlayIntegrityTokenRejectedError();
      }
      throw new GooglePlayIntegrityUnavailableError(
        "decode",
        response.status,
      );
    }

    const decoded = googleIntegrityVerdictSchema.parse(
      await readBoundedJson(response),
    ).tokenPayloadExternal;
    const timestampMilliseconds = Number(
      decoded.requestDetails.timestampMillis,
    );
    if (!Number.isSafeInteger(timestampMilliseconds)) {
      throw new Error("Google Play Integrity returned an invalid timestamp");
    }

    return {
      requestHash: decoded.requestDetails.requestHash,
      requestPackageName: decoded.requestDetails.requestPackageName,
      appPackageName: decoded.appIntegrity.packageName ?? null,
      certificateSha256Digests:
        decoded.appIntegrity.certificateSha256Digest,
      appRecognized:
        decoded.appIntegrity.appRecognitionVerdict === "PLAY_RECOGNIZED",
      licensed:
        decoded.accountDetails.appLicensingVerdict === "LICENSED",
      meetsDeviceIntegrity:
        decoded.deviceIntegrity.deviceRecognitionVerdict.includes(
          "MEETS_DEVICE_INTEGRITY",
        ),
      evaluatedAt: new Date(timestampMilliseconds),
    };
  }

  private async accessToken(): Promise<string> {
    const now = this.now();
    if (
      this.cachedAccessToken &&
      now < this.cachedAccessToken.refreshAfterMilliseconds
    ) {
      return this.cachedAccessToken.value;
    }
    this.pendingAccessToken ??= this.fetchAccessToken().finally(() => {
      this.pendingAccessToken = undefined;
    });
    return this.pendingAccessToken;
  }

  private async fetchAccessToken(): Promise<string> {
    const issuedAtSeconds = Math.floor(this.now() / 1_000);
    const assertion = this.serviceAccountAssertion(issuedAtSeconds);
    const response = await this.fetchImplementation(GOOGLE_TOKEN_ENDPOINT, {
      method: "POST",
      redirect: "error",
      cache: "no-store",
      signal: AbortSignal.timeout(GOOGLE_TOKEN_TIMEOUT_MILLISECONDS),
      headers: {
        Accept: JSON_MEDIA_TYPE,
        "Content-Type": FORM_MEDIA_TYPE,
      },
      body: new URLSearchParams({
        grant_type: JWT_BEARER_GRANT_TYPE,
        assertion,
      }).toString(),
    });
    if (!response.ok) {
      throw new GooglePlayIntegrityUnavailableError(
        "oauth",
        response.status,
      );
    }
    const token = googleAccessTokenSchema.parse(
      await readBoundedJson(response),
    );
    if (token.token_type.toLowerCase() !== "bearer") {
      throw new Error("Google returned an unsupported access token type");
    }

    this.cachedAccessToken = {
      value: token.access_token,
      refreshAfterMilliseconds:
        this.now() +
        Math.max(
          MIN_ACCESS_TOKEN_CACHE_MILLISECONDS,
          token.expires_in * 1_000 - ACCESS_TOKEN_REFRESH_MARGIN_MILLISECONDS,
        ),
    };
    return token.access_token;
  }

  private serviceAccountAssertion(issuedAtSeconds: number): string {
    const header = encodeJson({
      alg: "RS256",
      typ: "JWT",
    });
    const claims = encodeJson({
      iss: this.serviceAccountEmail,
      scope: GOOGLE_PLAY_INTEGRITY_SCOPE,
      aud: GOOGLE_TOKEN_ENDPOINT,
      iat: issuedAtSeconds,
      exp: issuedAtSeconds + SERVICE_ACCOUNT_ASSERTION_SECONDS,
    });
    const unsigned = `${header}.${claims}`;
    const signature = sign(
      "RSA-SHA256",
      Buffer.from(unsigned, "ascii"),
      this.privateKey,
    ).toString("base64url");
    return `${unsigned}.${signature}`;
  }
}

export class GooglePlayIntegrityTokenRejectedError extends Error {
  constructor() {
    super("Google Play rejected the integrity token");
    this.name = "GooglePlayIntegrityTokenRejectedError";
  }
}

export class GooglePlayIntegrityUnavailableError extends Error {
  constructor(
    public readonly stage: "oauth" | "decode",
    public readonly status: number,
  ) {
    super("Google Play Integrity verification is unavailable");
    this.name = "GooglePlayIntegrityUnavailableError";
  }
}

async function readBoundedJson(response: Response): Promise<unknown> {
  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().startsWith(JSON_MEDIA_TYPE)) {
    throw new Error("Google API returned a non-JSON response");
  }
  const declaredLength = response.headers.get("content-length");
  if (
    declaredLength &&
    (!/^[0-9]{1,7}$/.test(declaredLength) ||
      Number(declaredLength) > MAX_GOOGLE_RESPONSE_BYTES)
  ) {
    throw new Error("Google API response is too large");
  }
  if (!response.body) {
    throw new Error("Google API returned an empty response");
  }
  const reader = response.body.getReader();
  const chunks: Buffer[] = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      totalBytes += value.byteLength;
      if (totalBytes > MAX_GOOGLE_RESPONSE_BYTES) {
        await reader.cancel();
        throw new Error("Google API response is too large");
      }
      chunks.push(Buffer.from(value));
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = Buffer.concat(chunks, totalBytes);
  try {
    const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    try {
      return JSON.parse(text);
    } catch {
      throw new Error("Google API returned invalid JSON");
    }
  } finally {
    bytes.fill(0);
  }
}

function encodeJson(value: Record<string, string | number>): string {
  return Buffer.from(JSON.stringify(value), "utf8").toString("base64url");
}

const GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
const GOOGLE_PLAY_INTEGRITY_SCOPE =
  "https://www.googleapis.com/auth/playintegrity";
const JWT_BEARER_GRANT_TYPE =
  "urn:ietf:params:oauth:grant-type:jwt-bearer";
const JSON_MEDIA_TYPE = "application/json";
const FORM_MEDIA_TYPE = "application/x-www-form-urlencoded";
const SERVICE_ACCOUNT_ASSERTION_SECONDS = 60 * 60;
const ACCESS_TOKEN_REFRESH_MARGIN_MILLISECONDS = 2 * 60 * 1_000;
const MIN_ACCESS_TOKEN_CACHE_MILLISECONDS = 30 * 1_000;
const GOOGLE_TOKEN_TIMEOUT_MILLISECONDS = 10 * 1_000;
const PLAY_API_TIMEOUT_MILLISECONDS = 15 * 1_000;
const MAX_GOOGLE_RESPONSE_BYTES = 256 * 1_024;
