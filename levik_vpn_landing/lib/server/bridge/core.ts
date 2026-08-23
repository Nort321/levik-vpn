import "server-only";

import { randomBytes } from "node:crypto";

import { z } from "zod";

import {
  decodeSecret,
  hmacHex,
  sha256Hex,
} from "@/lib/server/crypto";
import { getEnvironment } from "@/lib/server/env";

const MAX_RESPONSE_BYTES = 128 * 1024;
const MAX_REQUEST_BYTES = 64 * 1024;
const REQUEST_TIMEOUT_MS = 12_000;

export class BridgeError extends Error {
  constructor(
    public readonly code: string,
    public readonly status: number,
    public readonly retryable: boolean,
  ) {
    super("The secure service request could not be completed");
    this.name = "BridgeError";
  }
}

type BridgeRequestOptions = {
  grant?: string;
  idempotencyKey?: string;
};

const errorResponseSchema = z
  .object({
    ok: z.literal(false).optional(),
    error: z
      .object({
        code: z.string().min(1).max(80),
        message: z.string().max(500),
        retryable: z.boolean(),
      })
      .strict()
      .optional(),
    code: z.string().min(1).max(80).optional(),
  })
  .passthrough();

async function readLimitedResponse(response: Response): Promise<string> {
  const declaredLength = Number(response.headers.get("content-length") ?? "0");
  if (Number.isFinite(declaredLength) && declaredLength > MAX_RESPONSE_BYTES) {
    throw new BridgeError("response_too_large", 502, false);
  }

  if (!response.body) {
    return "";
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let totalBytes = 0;
  let text = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      totalBytes += value.byteLength;
      if (totalBytes > MAX_RESPONSE_BYTES) {
        await reader.cancel();
        throw new BridgeError("response_too_large", 502, false);
      }
      text += decoder.decode(value, { stream: true });
    }
    return text + decoder.decode();
  } finally {
    reader.releaseLock();
  }
}

function normalizeBridgeError(status: number, body: unknown): BridgeError {
  const parsed = errorResponseSchema.safeParse(body);
  const code = parsed.success
    ? (parsed.data.error?.code ?? parsed.data.code ?? "bridge_rejected")
    : "bridge_rejected";
  return new BridgeError(
    code,
    status,
    status === 408 || status === 425 || status === 429 || status >= 500,
  );
}

export async function bridgeCall<Schema extends z.ZodType>(
  endpoint: `/${string}`,
  payload: Readonly<Record<string, unknown>>,
  responseSchema: Schema,
  options: BridgeRequestOptions = {},
): Promise<z.output<Schema>> {
  if (!/^\/[a-z0-9/-]+$/.test(endpoint) || endpoint.includes("//")) {
    throw new Error("Bridge endpoint is invalid");
  }
  if (
    options.idempotencyKey &&
    !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
      options.idempotencyKey,
    )
  ) {
    throw new Error("Idempotency key must be a canonical lowercase UUIDv4");
  }

  const environment = getEnvironment();
  const base = new URL(
    environment.BRIDGE_BASE_URL.endsWith("/")
      ? environment.BRIDGE_BASE_URL
      : `${environment.BRIDGE_BASE_URL}/`,
  );
  const url = new URL(endpoint.slice(1), base);
  if (
    url.origin !== base.origin ||
    !url.pathname.startsWith(base.pathname) ||
    url.search ||
    url.hash
  ) {
    throw new Error("Bridge URL escaped its configured boundary");
  }

  const body = JSON.stringify(payload);
  if (Buffer.byteLength(body) > MAX_REQUEST_BYTES) {
    throw new Error("Bridge request body is too large");
  }

  const timestamp = Math.floor(Date.now() / 1_000).toString();
  const nonce = randomBytes(16).toString("hex");
  const grant = options.grant ?? "";
  const idempotencyKey = options.idempotencyKey ?? "";
  const canonical = [
    "POST",
    url.pathname,
    timestamp,
    nonce,
    idempotencyKey,
    sha256Hex(grant),
    sha256Hex(body),
  ].join("\n");
  const signature = hmacHex(
    decodeSecret(environment.BRIDGE_HMAC_SECRET),
    canonical,
  );

  const requestHeaders = new Headers({
    Accept: "application/json",
    "Content-Type": "application/json",
    "X-Cabinet-Key-Id": environment.BRIDGE_KEY_ID,
    "X-Cabinet-Timestamp": timestamp,
    "X-Cabinet-Nonce": nonce,
    "X-Cabinet-Signature": `v1=${signature}`,
  });
  if (grant) {
    requestHeaders.set("X-Cabinet-Grant", grant);
  }
  if (idempotencyKey) {
    requestHeaders.set("Idempotency-Key", idempotencyKey);
  }

  let response: Response;
  try {
    response = await fetch(url, {
      method: "POST",
      headers: requestHeaders,
      body,
      cache: "no-store",
      credentials: "omit",
      redirect: "error",
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
  } catch {
    throw new BridgeError("bridge_unavailable", 503, true);
  }

  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().startsWith("application/json")) {
    throw new BridgeError("invalid_content_type", 502, false);
  }

  const responseText = await readLimitedResponse(response);
  let responseBody: unknown;
  try {
    responseBody = JSON.parse(responseText);
  } catch {
    throw new BridgeError("invalid_json", 502, false);
  }

  if (!response.ok) {
    throw normalizeBridgeError(response.status, responseBody);
  }

  const parsed = responseSchema.safeParse(responseBody);
  if (!parsed.success) {
    throw new BridgeError("invalid_response", 502, false);
  }
  return parsed.data;
}
