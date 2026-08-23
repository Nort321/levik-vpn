import "server-only";

import { z } from "zod";

import type { MobileRequestProof } from "@/lib/server/mobile-crypto";

const MAX_JSON_BODY_BYTES = 16 * 1_024;
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/;

const displayTextSchema = (maximumLength: number) =>
  z
    .string()
    .trim()
    .min(1)
    .max(maximumLength)
    .refine(
      (value) =>
        [...value].every((character) => {
          const codePoint = character.codePointAt(0) ?? 0;
          return codePoint >= 32 && codePoint !== 127;
        }),
      "must not contain control characters",
    );

export const mobileDeviceMetadataSchema = z
  .object({
    label: displayTextSchema(120),
    appVersion: z.string().regex(/^[A-Za-z0-9._+-]{1,40}$/),
    osVersion: displayTextSchema(80),
    model: displayTextSchema(120),
  })
  .strict();

export const mobileChallengeSchema = z
  .object({
    publicKeySpki: z.string().regex(/^[A-Za-z0-9_-]{342,1366}$/),
    deviceLabel: displayTextSchema(120),
    deviceModel: displayTextSchema(120),
    deviceOs: displayTextSchema(80),
    appVersion: z.string().regex(/^[A-Za-z0-9._+-]{1,40}$/),
    requestSigningAlgorithm: z.enum(["PS256", "RS256"]),
    profileEncryptionAlgorithm: z.enum([
      "RSA-OAEP-256+A256GCM",
      "RSA-OAEP+A256GCM",
    ]),
    accountActivationSupported: z.boolean().optional(),
  })
  .strict();

export const mobileLoginStatusSchema = z
  .object({
    loginToken: z.string().regex(TOKEN_PATTERN),
  })
  .strict();

export const mobileTunnelProfileSchema = z
  .object({
    subscriptionId: z.string().uuid(),
  })
  .strict();

export const mobileLogoutSchema = z.object({}).strict();

export const mobileRevokeDeviceSchema = z
  .object({
    subscriptionId: z.string().uuid(),
    deviceId: z.string().min(1).max(200),
  })
  .strict();

export const mobileRotateKeySchema = z
  .object({
    subscriptionId: z.string().uuid(),
  })
  .strict();

export type MobileDeviceMetadata = z.output<
  typeof mobileDeviceMetadataSchema
>;

export class MobileApiError extends Error {
  constructor(
    public readonly code: string,
    public readonly status: number,
    public readonly retryable = false,
  ) {
    super("The mobile API request could not be completed");
    this.name = "MobileApiError";
  }
}

export const MOBILE_NO_STORE_HEADERS = {
  "Cache-Control": "private, no-store, max-age=0",
  Pragma: "no-cache",
  "Content-Type": "application/json; charset=utf-8",
  Vary: "Authorization, X-Levik-Device-Id",
  "X-Content-Type-Options": "nosniff",
};

function decodeCanonicalNonce(value: string): boolean {
  if (!/^[A-Za-z0-9_-]{22}$/.test(value)) {
    return false;
  }
  const decoded = Buffer.from(value, "base64url");
  return (
    decoded.byteLength === 16 && decoded.toString("base64url") === value
  );
}

export function mobileRequestProof(headers: Headers): MobileRequestProof {
  const deviceId = headers.get("x-levik-device-id") ?? "";
  const timestamp = headers.get("x-levik-timestamp") ?? "";
  const nonce = headers.get("x-levik-nonce") ?? "";
  const signature = headers.get("x-levik-signature") ?? "";

  if (
    !/^[0-9a-f]{64}$/.test(deviceId) ||
    !/^[1-9][0-9]{9}$/.test(timestamp) ||
    !decodeCanonicalNonce(nonce) ||
    !/^[A-Za-z0-9_-]{512}$/.test(signature)
  ) {
    throw new MobileApiError("invalid_request_proof", 401);
  }
  return { deviceId, timestamp, nonce, signature };
}

export function mobileBearerToken(headers: Headers): string {
  const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(
    headers.get("authorization") ?? "",
  );
  if (!match?.[1]) {
    throw new MobileApiError("authentication_required", 401);
  }
  return match[1];
}

export function assertFreshMobileTimestamp(
  timestamp: string,
  now = Date.now(),
): void {
  const requestMilliseconds = Number(timestamp) * 1_000;
  if (
    !Number.isSafeInteger(requestMilliseconds) ||
    Math.abs(now - requestMilliseconds) > 2 * 60 * 1_000
  ) {
    throw new MobileApiError("stale_request", 401);
  }
}

export function assertMobileRequestTarget(
  request: Request,
  expectedPath: `/${string}`,
): void {
  const url = new URL(request.url);
  if (url.pathname !== expectedPath || url.search || url.hash) {
    throw new MobileApiError("invalid_request_target", 400);
  }
}

export async function readMobileJson<Schema extends z.ZodType>(
  request: Request,
  schema: Schema,
): Promise<{ body: Buffer; value: z.output<Schema> }> {
  const rawLength = request.headers.get("content-length");
  const contentType = request.headers.get("content-type") ?? "";
  if (
    rawLength === null ||
    !/^[1-9][0-9]{0,4}$/.test(rawLength) ||
    Number(rawLength) > MAX_JSON_BODY_BYTES ||
    request.headers.has("transfer-encoding") ||
    !contentType.toLowerCase().startsWith("application/json")
  ) {
    throw new MobileApiError("invalid_json_request", 415);
  }

  const body = Buffer.from(await request.arrayBuffer());
  if (
    body.byteLength !== Number(rawLength) ||
    body.byteLength > MAX_JSON_BODY_BYTES
  ) {
    throw new MobileApiError("invalid_json_request", 400);
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(body.toString("utf8"));
  } catch {
    throw new MobileApiError("invalid_json_request", 400);
  }
  const value = schema.safeParse(parsed);
  if (!value.success) {
    throw new MobileApiError("invalid_json_request", 400);
  }
  return { body, value: value.data };
}

export function emptyMobileRequestBody(request: Request): Buffer {
  const rawLength = request.headers.get("content-length");
  if (
    request.headers.has("transfer-encoding") ||
    (rawLength !== null && rawLength !== "0")
  ) {
    throw new MobileApiError("invalid_request_body", 400);
  }
  return Buffer.alloc(0);
}
