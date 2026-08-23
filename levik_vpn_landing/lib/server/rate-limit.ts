import "server-only";

import { decodeSecret, hmacBase64Url } from "@/lib/server/crypto";
import { query } from "@/lib/server/db";
import { getEnvironment } from "@/lib/server/env";

type RateLimitOptions = {
  scope: string;
  identifier: string;
  limit: number;
  windowSeconds: number;
};

export type RateLimitResult = {
  allowed: boolean;
  remaining: number;
  retryAfterSeconds: number;
};

export async function consumeRateLimit({
  scope,
  identifier,
  limit,
  windowSeconds,
}: RateLimitOptions): Promise<RateLimitResult> {
  if (
    !/^[a-z0-9:_-]{1,80}$/.test(scope) ||
    !Number.isSafeInteger(limit) ||
    limit < 1 ||
    !Number.isSafeInteger(windowSeconds) ||
    windowSeconds < 1
  ) {
    throw new Error("Invalid rate limit configuration");
  }

  const now = Date.now();
  const windowMilliseconds = windowSeconds * 1_000;
  const bucketStart = new Date(
    Math.floor(now / windowMilliseconds) * windowMilliseconds,
  );
  const resetAt = bucketStart.getTime() + windowMilliseconds;
  const environment = getEnvironment();
  const keyHash = Buffer.from(
    hmacBase64Url(
      decodeSecret(environment.AUDIT_HMAC_KEY),
      `rate-limit:v1:${scope}:${identifier}`,
    ),
    "base64url",
  );

  const result = await query<{ request_count: number }>(
    `
      INSERT INTO web_rate_limits (
        key_hash,
        bucket_start,
        request_count,
        expires_at
      )
      VALUES ($1, $2, 1, $3)
      ON CONFLICT (key_hash, bucket_start)
      DO UPDATE SET request_count =
        LEAST(web_rate_limits.request_count + 1, 2147483647)
      RETURNING request_count
    `,
    [keyHash, bucketStart, new Date(resetAt + windowMilliseconds)],
  );

  const count = result.rows[0]?.request_count ?? limit + 1;
  return {
    allowed: count <= limit,
    remaining: Math.max(0, limit - count),
    retryAfterSeconds: Math.max(1, Math.ceil((resetAt - now) / 1_000)),
  };
}
