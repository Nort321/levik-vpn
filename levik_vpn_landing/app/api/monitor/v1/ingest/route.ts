import { createHmac, timingSafeEqual } from "node:crypto";

import { z } from "zod";

import { monitorServices } from "@/lib/monitor/catalog";
import { getEnvironment } from "@/lib/server/env";
import { withTransaction } from "@/lib/server/db";

export const runtime = "nodejs";

const MAX_BODY_BYTES = 96 * 1024;
const MAX_CLOCK_SKEW_SECONDS = 5 * 60;
const knownServiceSlugs = new Set(monitorServices.map((service) => service.slug));

const resultSchema = z.object({
  serviceSlug: z.string().min(2).max(40).refine((slug) => knownServiceSlugs.has(slug)),
  state: z.enum(["operational", "degraded", "outage"]),
  availability: z.number().int().min(0).max(100),
  latencyMs: z.number().int().min(0).max(120_000).nullable(),
  checks: z.record(z.string().max(40), z.unknown()),
});

const payloadSchema = z.object({
  batchId: z.string().uuid(),
  measuredAt: z.string().datetime({ offset: true }),
  probe: z.object({
    id: z.string().regex(/^[a-z0-9][a-z0-9_-]{2,39}$/),
    label: z.string().min(2).max(80),
    countryCode: z.string().regex(/^[A-Z]{2}$/),
    region: z.string().min(2).max(80).nullable(),
    agentVersion: z.string().min(1).max(32),
  }),
  results: z.array(resultSchema).min(1).max(32),
});

function unauthorized(): Response {
  return Response.json({ error: "unauthorized" }, { status: 401 });
}

export async function POST(request: Request) {
  const contentLength = Number(request.headers.get("content-length") ?? "0");
  if (!Number.isFinite(contentLength) || contentLength > MAX_BODY_BYTES) {
    return Response.json({ error: "payload_too_large" }, { status: 413 });
  }

  const probeId = request.headers.get("x-levik-probe") ?? "";
  const timestamp = request.headers.get("x-levik-timestamp") ?? "";
  const signature = request.headers.get("x-levik-signature") ?? "";
  const parsedTimestamp = Number(timestamp);
  if (
    !Number.isInteger(parsedTimestamp) ||
    Math.abs(Math.floor(Date.now() / 1000) - parsedTimestamp) > MAX_CLOCK_SKEW_SECONDS
  ) {
    return unauthorized();
  }

  const secret = getEnvironment().monitorProbeSecrets.get(probeId);
  if (!secret || !/^[A-Za-z0-9_-]{43}$/.test(signature)) {
    return unauthorized();
  }

  const body = await request.text();
  if (Buffer.byteLength(body, "utf8") > MAX_BODY_BYTES) {
    return Response.json({ error: "payload_too_large" }, { status: 413 });
  }
  const expected = createHmac("sha256", Buffer.from(secret, "base64url"))
    .update(`${timestamp}.${body}`)
    .digest();
  const received = Buffer.from(signature, "base64url");
  if (received.byteLength !== expected.byteLength || !timingSafeEqual(received, expected)) {
    return unauthorized();
  }

  let decoded: unknown;
  try {
    decoded = JSON.parse(body) as unknown;
  } catch {
    return Response.json({ error: "invalid_json" }, { status: 400 });
  }
  const parsed = payloadSchema.safeParse(decoded);
  if (!parsed.success || parsed.data.probe.id !== probeId) {
    return Response.json({ error: "invalid_payload" }, { status: 400 });
  }
  const measuredAt = new Date(parsed.data.measuredAt);
  if (Math.abs(Date.now() - measuredAt.getTime()) > MAX_CLOCK_SKEW_SECONDS * 1000) {
    return Response.json({ error: "stale_measurement" }, { status: 400 });
  }

  try {
    await withTransaction(async (client) => {
      await client.query(
        `
          INSERT INTO monitor_probes (
            id, label, country_code, region, agent_version, last_seen_at
          ) VALUES ($1, $2, $3, $4, $5, now())
          ON CONFLICT (id) DO UPDATE SET
            label = EXCLUDED.label,
            country_code = EXCLUDED.country_code,
            region = EXCLUDED.region,
            agent_version = EXCLUDED.agent_version,
            last_seen_at = now()
        `,
        [
          parsed.data.probe.id,
          parsed.data.probe.label,
          parsed.data.probe.countryCode,
          parsed.data.probe.region,
          parsed.data.probe.agentVersion,
        ],
      );
      const batch = await client.query(
        `
          INSERT INTO monitor_batches (batch_id, probe_id, measured_at)
          VALUES ($1, $2, $3)
          ON CONFLICT (batch_id) DO NOTHING
          RETURNING batch_id
        `,
        [parsed.data.batchId, parsed.data.probe.id, measuredAt],
      );
      if (batch.rowCount === 0) return;
      for (const result of parsed.data.results) {
        await client.query(
          `
            INSERT INTO monitor_measurements (
              batch_id, service_slug, state, availability, latency_ms, checks
            ) VALUES ($1, $2, $3, $4, $5, $6::jsonb)
          `,
          [
            parsed.data.batchId,
            result.serviceSlug,
            result.state,
            result.availability,
            result.latencyMs,
            JSON.stringify(result.checks),
          ],
        );
      }
    });
  } catch {
    return Response.json({ error: "temporarily_unavailable" }, { status: 503 });
  }

  return new Response(null, { status: 204 });
}
