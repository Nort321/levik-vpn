import "server-only";

import type { KeyObject } from "node:crypto";

import type { BridgeSnapshot } from "@/lib/server/bridge/cabinet";
import { sha256Hex } from "@/lib/server/crypto";
import { getEnvironment } from "@/lib/server/env";
import {
  MobileApiError,
  type MobileDeviceMetadata,
} from "@/lib/server/mobile-api";
import {
  encryptMobilePayload,
  type EncryptedMobilePayload,
  type MobileProfileEncryptionAlgorithm,
} from "@/lib/server/mobile-crypto";

const MAX_SUBSCRIPTION_BYTES = 1_024 * 1_024;
const SUBSCRIPTION_TIMEOUT_MS = 15_000;
const ACCEPTED_MEDIA_TYPES = new Set([
  "application/json",
  "application/octet-stream",
  "application/x-yaml",
  "application/yaml",
  "text/plain",
  "text/x-yaml",
  "text/yaml",
]);
const DIRECT_CIDRS = [
  "10.0.0.0/8",
  "100.64.0.0/10",
  "127.0.0.0/8",
  "169.254.0.0/16",
  "172.16.0.0/12",
  "192.168.0.0/16",
] as const;

type BridgeSubscription = BridgeSnapshot["subscriptions"][number];

export type MobileTunnelProfile = {
  version: 1;
  profileId: string;
  subscriptionId: string;
  issuedAt: string;
  subscriptionExpiresAt: string | null;
  source: {
    mediaType: string;
    content: string;
  };
  routing: {
    directCidrs: string[];
  };
};

function validatedSubscriptionUrl(rawUrl: string): URL {
  let url: URL;
  try {
    url = new URL(rawUrl);
  } catch {
    throw new MobileApiError("profile_unavailable", 502);
  }
  const environment = getEnvironment();
  if (
    rawUrl.length > 4_096 ||
    url.protocol !== "https:" ||
    url.username ||
    url.password ||
    url.hash ||
    !environment.subscriptionUrlAllowedOrigins.has(
      url.origin.toLowerCase(),
    )
  ) {
    throw new MobileApiError("profile_unavailable", 502);
  }
  return url;
}

function normalizedMediaType(response: Response, content: string): string {
  const rawContentType = response.headers.get("content-type") ?? "";
  const mediaType = rawContentType
    .split(";", 1)[0]
    ?.trim()
    .toLowerCase();
  if (mediaType) {
    if (
      mediaType === "text/html" ||
      mediaType === "application/xhtml+xml" ||
      !ACCEPTED_MEDIA_TYPES.has(mediaType)
    ) {
      throw new MobileApiError("invalid_profile_response", 502);
    }
    return mediaType;
  }
  const trimmed = content.trimStart();
  return trimmed.startsWith("{") || trimmed.startsWith("[")
    ? "application/json"
    : "text/plain";
}

async function readBoundedSubscription(response: Response): Promise<Buffer> {
  const declaredLength = response.headers.get("content-length");
  if (
    declaredLength &&
    (/[^0-9]/.test(declaredLength) ||
      Number(declaredLength) > MAX_SUBSCRIPTION_BYTES)
  ) {
    throw new MobileApiError("invalid_profile_response", 502);
  }
  if (!response.body) {
    throw new MobileApiError("invalid_profile_response", 502);
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
      if (totalBytes > MAX_SUBSCRIPTION_BYTES) {
        await reader.cancel();
        throw new MobileApiError("profile_too_large", 502);
      }
      chunks.push(Buffer.from(value));
    }
  } finally {
    reader.releaseLock();
  }
  if (totalBytes === 0) {
    throw new MobileApiError("invalid_profile_response", 502);
  }
  return Buffer.concat(chunks, totalBytes);
}

export function validateMobileProfileContent(
  bytes: Buffer,
  response: Response,
): { mediaType: string; content: string } {
  let content: string;
  try {
    content = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new MobileApiError("invalid_profile_response", 502);
  }
  const trimmed = content.trim();
  if (
    !trimmed ||
    content.includes("\0") ||
    /^(?:<!doctype\s+html|<html(?:\s|>))/i.test(trimmed)
  ) {
    throw new MobileApiError("invalid_profile_response", 502);
  }
  return {
    mediaType: normalizedMediaType(response, content),
    content,
  };
}

export function mobileProfileSubscriptionExpiresAt(
  subscriptionExpiresAt: string | null,
  issuedAt: Date,
): string | null {
  if (!Number.isFinite(issuedAt.getTime())) {
    throw new MobileApiError("invalid_profile_expiry", 500);
  }
  if (!subscriptionExpiresAt) {
    return null;
  }

  const subscriptionExpiry = new Date(subscriptionExpiresAt).getTime();
  if (
    !Number.isFinite(subscriptionExpiry) ||
    subscriptionExpiry <= issuedAt.getTime()
  ) {
    throw new MobileApiError("subscription_not_found", 404);
  }
  return new Date(subscriptionExpiry).toISOString();
}

export async function fetchMobileSubscriptionSource(
  subscriptionUrl: string,
  deviceId: string,
  device: MobileDeviceMetadata,
): Promise<{ mediaType: string; content: string }> {
  const url = validatedSubscriptionUrl(subscriptionUrl);
  let response: Response;
  try {
    response = await fetch(url, {
      method: "GET",
      headers: {
        Accept:
          "application/json, text/plain;q=0.9, application/yaml;q=0.8, application/octet-stream;q=0.7",
        "User-Agent": `LevikVPN-Android/${device.appVersion}`,
        "X-HWID": deviceId,
        "X-Device-OS": "Android",
        "X-Ver-OS": device.osVersion,
        "X-Device-Model": device.model,
      },
      cache: "no-store",
      credentials: "omit",
      redirect: "error",
      signal: AbortSignal.timeout(SUBSCRIPTION_TIMEOUT_MS),
    });
  } catch {
    throw new MobileApiError("profile_upstream_unavailable", 503, true);
  }

  if (!response.ok) {
    const hasDeviceLimit =
      response.headers.has("x-hwid-limit") ||
      response.headers.has("x-hwid-device-limit");
    if ((response.status === 403 || response.status === 429) && hasDeviceLimit) {
      throw new MobileApiError("device_limit_reached", 409);
    }
    throw new MobileApiError(
      response.status === 429
        ? "profile_rate_limited"
        : "profile_upstream_unavailable",
      response.status === 429 ? 429 : 503,
      response.status === 429 || response.status >= 500,
    );
  }

  const bytes = await readBoundedSubscription(response);
  return validateMobileProfileContent(bytes, response);
}

export async function buildEncryptedMobileProfile(input: {
  subscription: BridgeSubscription;
  sessionPublicId: string;
  deviceId: string;
  device: MobileDeviceMetadata;
  publicKey: KeyObject;
  profileEncryptionAlgorithm: MobileProfileEncryptionAlgorithm;
}): Promise<EncryptedMobilePayload> {
  if (!input.subscription.subscriptionUrl) {
    throw new MobileApiError("subscription_not_found", 404);
  }
  const issuedAtDate = new Date();
  const issuedAt = issuedAtDate.toISOString();
  const subscriptionExpiresAt = mobileProfileSubscriptionExpiresAt(
    input.subscription.expireAt,
    issuedAtDate,
  );
  const source = await fetchMobileSubscriptionSource(
    input.subscription.subscriptionUrl,
    input.deviceId,
    input.device,
  );
  const profileId = sha256Hex(
    [
      "mobile-profile-v1",
      input.subscription.uuid,
      input.deviceId,
      issuedAt,
    ].join("\n"),
  );
  const profile: MobileTunnelProfile = {
    version: 1,
    profileId,
    subscriptionId: input.subscription.uuid,
    issuedAt,
    subscriptionExpiresAt,
    source,
    routing: {
      directCidrs: [...DIRECT_CIDRS],
    },
  };
  const aad = Buffer.from(
    [
      "levik-mobile-profile-v1",
      input.deviceId,
      input.sessionPublicId,
      input.subscription.uuid,
      profileId,
    ].join("\n"),
    "utf8",
  );
  return encryptMobilePayload(
    Buffer.from(JSON.stringify(profile), "utf8"),
    input.publicKey,
    aad,
    input.profileEncryptionAlgorithm,
  );
}
