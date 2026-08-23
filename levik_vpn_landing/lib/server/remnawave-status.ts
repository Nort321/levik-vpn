import "server-only";

import { performance } from "node:perf_hooks";

import { z } from "zod";

import { getEnvironment } from "@/lib/server/env";
import type {
  PublicServerStatus,
  ServerState,
  StatusSnapshot,
} from "@/lib/status";

const upstreamNodeSchema = z.object({
  uuid: z.string().uuid(),
  countryCode: z.string().regex(/^[A-Za-z]{2}$/),
  isConnected: z.boolean(),
  isDisabled: z.boolean(),
  isConnecting: z.boolean(),
  lastStatusChange: z.string().datetime().nullable(),
  trafficUsedBytes: z.number().nonnegative().finite(),
  system: z
    .object({
      stats: z.object({
        uptime: z.number().nonnegative().finite(),
        loadAvg: z.array(z.number().nonnegative().finite()).max(3),
      }),
    })
    .nullable(),
});

const upstreamResponseSchema = z.object({
  response: z.array(upstreamNodeSchema).max(500),
});

const CACHE_TTL_MS = 15_000;
const STALE_TTL_MS = 10 * 60_000;

let cachedSnapshot: StatusSnapshot | undefined;
let inFlight: Promise<StatusSnapshot> | undefined;

function serverState(node: z.infer<typeof upstreamNodeSchema>): ServerState {
  if (node.isDisabled) return "maintenance";
  if (node.isConnected) return "online";
  if (node.isConnecting) return "degraded";
  return "offline";
}

function sanitizeNode(
  node: z.infer<typeof upstreamNodeSchema>,
): PublicServerStatus {
  return {
    id: node.uuid,
    countryCode: node.countryCode.toUpperCase(),
    state: serverState(node),
    load: node.system?.stats.loadAvg[0] ?? null,
    uptimeSeconds: node.system?.stats.uptime ?? null,
    trafficUsedBytes: node.trafficUsedBytes,
    lastStatusChange: node.lastStatusChange,
  };
}

async function fetchSnapshot(): Promise<StatusSnapshot> {
  const environment = getEnvironment();
  const startedAt = performance.now();
  const upstream = await fetch(environment.REMNAWAVE_STATUS_URL, {
    cache: "no-store",
    redirect: "error",
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${environment.REMNAWAVE_API_TOKEN}`,
    },
    signal: AbortSignal.timeout(8_000),
  });

  if (!upstream.ok) {
    throw new Error("remnawave_status_upstream_error");
  }

  const parsed = upstreamResponseSchema.safeParse(await upstream.json());
  if (!parsed.success) {
    throw new Error("invalid_remnawave_status_response");
  }

  return {
    servers: parsed.data.response
      .map(sanitizeNode)
      .sort((left, right) =>
        left.countryCode.localeCompare(right.countryCode, "en"),
      ),
    fetchedAt: new Date().toISOString(),
    source: "live",
    controlLatencyMs: Math.max(1, Math.round(performance.now() - startedAt)),
  };
}

export async function getStatusSnapshot(): Promise<StatusSnapshot> {
  const cacheAge = cachedSnapshot
    ? Date.now() - new Date(cachedSnapshot.fetchedAt).getTime()
    : Number.POSITIVE_INFINITY;
  if (cachedSnapshot && cacheAge < CACHE_TTL_MS) {
    return cachedSnapshot;
  }

  inFlight ??= fetchSnapshot()
    .then((snapshot) => {
      cachedSnapshot = snapshot;
      return snapshot;
    })
    .catch((error: unknown) => {
      if (cachedSnapshot && cacheAge < STALE_TTL_MS) {
        return { ...cachedSnapshot, source: "stale" as const };
      }
      throw error;
    })
    .finally(() => {
      inFlight = undefined;
    });

  return inFlight;
}
