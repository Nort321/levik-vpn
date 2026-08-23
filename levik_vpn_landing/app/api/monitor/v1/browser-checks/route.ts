import { z } from "zod";

import { getMonitorService } from "@/lib/monitor/catalog";
import { withTransaction } from "@/lib/server/db";
import { getLocalGeoData } from "@/lib/server/geoip";
import { consumeRateLimit } from "@/lib/server/rate-limit";
import { clientAddressFromHeaders } from "@/lib/server/security";

export const dynamic = "force-dynamic";
export const runtime = "nodejs";

const MAX_BODY_BYTES = 24 * 1024;
const MAX_CLOCK_SKEW_MS = 2 * 60_000;
const MONITOR_ORIGIN = "https://mon.leviknet.com";
const NO_STORE_HEADERS = { "Cache-Control": "private, no-store, max-age=0" };

const checkSchema = z.object({
  id: z.string().regex(/^[a-z0-9][a-z0-9_-]{1,39}$/),
  reachable: z.boolean(),
  latencyMs: z.number().int().min(0).max(120_000).nullable(),
});
const payloadSchema = z.object({
  mode: z.enum(["diagnostic", "report"]),
  measuredAt: z.string().datetime({ offset: true }),
  results: z.array(z.object({
    serviceSlug: z.string().regex(/^[a-z0-9][a-z0-9-]{1,39}$/),
    checks: z.array(checkSchema).min(1).max(16),
  })).min(1).max(16),
});

function validOrigin(headers: Headers): boolean {
  const origin = headers.get("origin");
  const host = (headers.get("x-forwarded-host") ?? headers.get("host") ?? "")
    .split(":")[0]
    .toLowerCase();
  const fetchSite = headers.get("sec-fetch-site");
  return origin === MONITOR_ORIGIN && host === "mon.leviknet.com" &&
    (!fetchSite || fetchSite === "same-origin");
}

function validServiceChecks(
  serviceSlug: string,
  checks: readonly z.infer<typeof checkSchema>[],
): boolean {
  const service = getMonitorService(serviceSlug);
  if (!service) return false;
  const expected = new Set(["homepage", ...service.checks.map((check) => check.id)]);
  const supplied = new Set(checks.map((check) => check.id));
  return supplied.size === checks.length && supplied.size === expected.size &&
    [...supplied].every((id) => expected.has(id));
}

export async function POST(request: Request) {
  try {
    if (!validOrigin(request.headers)) {
      return Response.json({ error: "request_rejected" }, { status: 403, headers: NO_STORE_HEADERS });
    }
    if (request.headers.get("content-type")?.split(";", 1)[0] !== "application/json") {
      return Response.json({ error: "invalid_payload" }, { status: 415, headers: NO_STORE_HEADERS });
    }
    const contentLength = Number(request.headers.get("content-length") ?? "0");
    if (!Number.isSafeInteger(contentLength) || contentLength < 1 || contentLength > MAX_BODY_BYTES) {
      return Response.json({ error: "invalid_payload" }, { status: 413, headers: NO_STORE_HEADERS });
    }
    const body = await request.text();
    if (Buffer.byteLength(body) > MAX_BODY_BYTES) {
      return Response.json({ error: "invalid_payload" }, { status: 413, headers: NO_STORE_HEADERS });
    }
    let json: unknown;
    try {
      json = JSON.parse(body) as unknown;
    } catch {
      return Response.json({ error: "invalid_payload" }, { status: 400, headers: NO_STORE_HEADERS });
    }
    const decoded = payloadSchema.safeParse(json);
    if (
      !decoded.success ||
      (decoded.data.mode === "report" && decoded.data.results.length !== 1) ||
      Math.abs(Date.now() - new Date(decoded.data.measuredAt).getTime()) > MAX_CLOCK_SKEW_MS ||
      !decoded.data.results.every((result) => validServiceChecks(result.serviceSlug, result.checks))
    ) {
      return Response.json({ error: "invalid_payload" }, { status: 400, headers: NO_STORE_HEADERS });
    }

    const clientAddress = clientAddressFromHeaders(request.headers);
    const limit = await consumeRateLimit({
      scope: "monitor-browser-check-ip",
      identifier: clientAddress,
      limit: 12,
      windowSeconds: 10 * 60,
    });
    if (!limit.allowed) {
      return Response.json(
        { error: "rate_limited" },
        {
          status: 429,
          headers: { ...NO_STORE_HEADERS, "Retry-After": limit.retryAfterSeconds.toString() },
        },
      );
    }

    const geo = await getLocalGeoData(clientAddress).catch(() => null);
    await withTransaction(async (client) => {
      for (const result of decoded.data.results) {
        const reachableChecks = result.checks.filter((check) => check.reachable).length;
        const latencyValues = result.checks.flatMap((check) =>
          check.reachable && check.latencyMs !== null ? [check.latencyMs] : [],
        );
        const state = reachableChecks === 0
          ? "unreachable"
          : reachableChecks === result.checks.length
            ? "reachable"
            : "partial";
        await client.query(
          `
            INSERT INTO monitor_browser_checks (
              measured_at, mode, service_slug, state, reachable_checks,
              total_checks, latency_ms, country_code, region, city, asn,
              provider, checks
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13::jsonb)
          `,
          [
            new Date(decoded.data.measuredAt),
            decoded.data.mode,
            result.serviceSlug,
            state,
            reachableChecks,
            result.checks.length,
            latencyValues.length === 0 ? null : Math.max(...latencyValues),
            geo?.countryCode ?? null,
            geo?.region ?? null,
            geo?.city ?? null,
            geo?.asn ?? null,
            geo?.provider ?? null,
            JSON.stringify(result.checks),
          ],
        );
      }
    });

    return new Response(null, { status: 202, headers: NO_STORE_HEADERS });
  } catch {
    return Response.json(
      { error: "temporarily_unavailable" },
      { status: 503, headers: NO_STORE_HEADERS },
    );
  }
}
