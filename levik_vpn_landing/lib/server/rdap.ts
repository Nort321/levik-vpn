import "server-only";

import { isIP } from "node:net";

import ipaddr from "ipaddr.js";
import { z } from "zod";

import { query } from "@/lib/server/db";

const BOOTSTRAP_CACHE_TTL_MS = 24 * 60 * 60_000;
const RDAP_CACHE_TTL_MS = 30 * 24 * 60 * 60_000;
const MAX_RESPONSE_BYTES = 512 * 1_024;
const ALLOWED_RDAP_HOSTS = new Set([
  "rdap.afrinic.net",
  "rdap.apnic.net",
  "rdap.arin.net",
  "rdap.db.ripe.net",
  "rdap.lacnic.net",
]);

const bootstrapSchema = z.object({
  services: z.array(
    z.tuple([
      z.array(z.string().max(64)).max(512),
      z.array(z.string().url().max(240)).max(10),
    ]),
  ),
});
const rdapSchema = z.object({
  objectClassName: z.literal("ip network"),
  handle: z.string().max(160).optional(),
  startAddress: z.string().max(64),
  endAddress: z.string().max(64),
  ipVersion: z.enum(["v4", "v6"]).optional(),
  name: z.string().max(240).optional(),
  type: z.string().max(80).optional(),
  country: z.string().regex(/^[A-Za-z]{2}$/).optional(),
  events: z
    .array(
      z.object({
        eventAction: z.string().max(80),
        eventDate: z.string().datetime({ offset: true }),
      }),
    )
    .max(100)
    .optional(),
  cidr0_cidrs: z
    .array(
      z.union([
        z.object({
          v4prefix: z.string().max(15),
          length: z.number().int().min(0).max(32),
        }),
        z.object({
          v6prefix: z.string().max(45),
          length: z.number().int().min(0).max(128),
        }),
      ]),
    )
    .max(128)
    .optional(),
});

type BootstrapEntry = {
  cidr: string;
  baseUrl: string;
};
type BootstrapCache = {
  expiresAt: number;
  entries: BootstrapEntry[];
};
type RdapCacheRow = {
  network: string;
  registry: string;
  network_name: string | null;
  network_handle: string | null;
  network_type: string | null;
  country_code: string | null;
  range_start: string;
  range_end: string;
  registered_at: Date | null;
  updated_at: Date | null;
};

export type RdapData = {
  network: string;
  registry: string;
  name: string | null;
  handle: string | null;
  type: string | null;
  countryCode: string | null;
  rangeStart: string;
  rangeEnd: string;
  registeredAt: string | null;
  updatedAt: string | null;
};

declare global {
  var __leviknetRdapBootstrap: Partial<Record<4 | 6, BootstrapCache>> | undefined;
  var __leviknetRdapRequests: Map<string, Promise<RdapData | null>> | undefined;
}

function validRdapBaseUrl(value: string): string | null {
  try {
    const url = new URL(value);
    if (
      url.protocol !== "https:" ||
      url.username ||
      url.password ||
      url.search ||
      url.hash ||
      !ALLOWED_RDAP_HOSTS.has(url.hostname)
    ) {
      return null;
    }
    return url.toString();
  } catch {
    return null;
  }
}

function contains(cidr: string, address: string): boolean {
  try {
    const parsedAddress = ipaddr.parse(address);
    const [network, prefixLength] = ipaddr.parseCIDR(cidr);
    return (
      parsedAddress.kind() === network.kind() &&
      parsedAddress.match(network, prefixLength)
    );
  } catch {
    return false;
  }
}

function compareBytes(left: readonly number[], right: readonly number[]): number {
  for (let index = 0; index < left.length; index += 1) {
    const difference = (left[index] ?? 0) - (right[index] ?? 0);
    if (difference !== 0) return difference;
  }
  return left.length - right.length;
}

function addressIsWithinRange(
  address: string,
  start: string,
  end: string,
): boolean {
  try {
    const parsedAddress = ipaddr.parse(address);
    const parsedStart = ipaddr.parse(start);
    const parsedEnd = ipaddr.parse(end);
    if (
      parsedAddress.kind() !== parsedStart.kind() ||
      parsedAddress.kind() !== parsedEnd.kind()
    ) {
      return false;
    }
    const bytes = parsedAddress.toByteArray();
    return (
      compareBytes(bytes, parsedStart.toByteArray()) >= 0 &&
      compareBytes(bytes, parsedEnd.toByteArray()) <= 0
    );
  } catch {
    return false;
  }
}

async function responseJson(response: Response): Promise<unknown> {
  const advertisedLength = Number(response.headers.get("content-length") ?? "0");
  if (advertisedLength > MAX_RESPONSE_BYTES) {
    await response.body?.cancel();
    throw new Error("RDAP response is too large");
  }
  const body = await response.text();
  if (Buffer.byteLength(body) > MAX_RESPONSE_BYTES) {
    throw new Error("RDAP response is too large");
  }
  return JSON.parse(body) as unknown;
}

async function bootstrapEntries(version: 4 | 6): Promise<BootstrapEntry[]> {
  const cache = globalThis.__leviknetRdapBootstrap?.[version];
  if (cache && cache.expiresAt > Date.now()) return cache.entries;

  const response = await fetch(`https://data.iana.org/rdap/ipv${version}.json`, {
    cache: "no-store",
    redirect: "error",
    headers: { Accept: "application/json" },
    signal: AbortSignal.timeout(5_000),
  });
  if (!response.ok) throw new Error("IANA RDAP bootstrap is unavailable");
  const parsed = bootstrapSchema.safeParse(await responseJson(response));
  if (!parsed.success) throw new Error("IANA RDAP bootstrap is invalid");

  const entries = parsed.data.services.flatMap(([cidrs, urls]) => {
    const baseUrl = urls.map(validRdapBaseUrl).find(Boolean);
    if (!baseUrl) return [];
    return cidrs
      .filter((cidr) => {
        try {
          return ipaddr.parseCIDR(cidr)[0].kind() === `ipv${version}`;
        } catch {
          return false;
        }
      })
      .map((cidr) => ({ cidr, baseUrl }));
  });
  if (entries.length === 0) throw new Error("IANA RDAP bootstrap is empty");

  globalThis.__leviknetRdapBootstrap ??= {};
  globalThis.__leviknetRdapBootstrap[version] = {
    entries,
    expiresAt: Date.now() + BOOTSTRAP_CACHE_TTL_MS,
  };
  return entries;
}

function registryName(baseUrl: string): string {
  switch (new URL(baseUrl).hostname) {
    case "rdap.afrinic.net":
      return "AFRINIC";
    case "rdap.apnic.net":
      return "APNIC";
    case "rdap.arin.net":
      return "ARIN";
    case "rdap.lacnic.net":
      return "LACNIC";
    default:
      return "RIPE NCC";
  }
}

function eventDate(
  events: z.infer<typeof rdapSchema>["events"],
  actions: ReadonlySet<string>,
): string | null {
  const event = events?.find((candidate) =>
    actions.has(candidate.eventAction.toLowerCase()),
  );
  return event?.eventDate ?? null;
}

function responseNetworks(
  response: z.infer<typeof rdapSchema>,
  address: string,
): string[] {
  const networks = (response.cidr0_cidrs ?? []).map((entry) =>
    "v4prefix" in entry
      ? `${entry.v4prefix}/${entry.length}`
      : `${entry.v6prefix}/${entry.length}`,
  );
  const matching = networks.filter((network) => contains(network, address));
  if (matching.length > 0) return matching;
  return [`${address}/${isIP(address) === 6 ? 128 : 32}`];
}

export function rdapDataFromResponse(
  value: unknown,
  baseUrl: string,
  address: string,
): { data: RdapData; networks: string[] } | null {
  const parsed = rdapSchema.safeParse(value);
  if (!parsed.success) return null;
  if (
    isIP(parsed.data.startAddress) === 0 ||
    isIP(parsed.data.endAddress) === 0 ||
    !addressIsWithinRange(
      address,
      parsed.data.startAddress,
      parsed.data.endAddress,
    )
  ) {
    return null;
  }

  const networks = responseNetworks(parsed.data, address);
  const selectedNetwork = [...networks].sort(
    (left, right) =>
      Number(right.split("/")[1]) - Number(left.split("/")[1]),
  )[0];
  if (!selectedNetwork) return null;
  return {
    networks,
    data: {
      network: selectedNetwork,
      registry: registryName(baseUrl),
      name: parsed.data.name ?? null,
      handle: parsed.data.handle ?? null,
      type: parsed.data.type ?? null,
      countryCode: parsed.data.country?.toUpperCase() ?? null,
      rangeStart: parsed.data.startAddress,
      rangeEnd: parsed.data.endAddress,
      registeredAt: eventDate(
        parsed.data.events,
        new Set(["registration"]),
      ),
      updatedAt: eventDate(
        parsed.data.events,
        new Set(["last changed", "last update of rdap database"]),
      ),
    },
  };
}

function fromCacheRow(row: RdapCacheRow): RdapData {
  return {
    network: row.network,
    registry: row.registry,
    name: row.network_name,
    handle: row.network_handle,
    type: row.network_type,
    countryCode: row.country_code,
    rangeStart: row.range_start,
    rangeEnd: row.range_end,
    registeredAt: row.registered_at?.toISOString() ?? null,
    updatedAt: row.updated_at?.toISOString() ?? null,
  };
}

async function cachedRdapData(
  address: string,
  freshOnly: boolean,
): Promise<RdapData | null> {
  const result = await query<RdapCacheRow>(
    `
      SELECT
        network::text,
        registry,
        network_name,
        network_handle,
        network_type,
        country_code,
        host(range_start) AS range_start,
        host(range_end) AS range_end,
        registered_at,
        updated_at
      FROM ip_rdap_cache
      WHERE network >>= $1::inet
        AND ($2::boolean = false OR expires_at > now())
      ORDER BY masklen(network) DESC, fetched_at DESC
      LIMIT 1
    `,
    [address, freshOnly],
  );
  return result.rows[0] ? fromCacheRow(result.rows[0]) : null;
}

async function storeRdapData(
  networks: readonly string[],
  data: RdapData,
): Promise<void> {
  await Promise.all(
    networks.map((network) =>
      query(
        `
          INSERT INTO ip_rdap_cache (
            network,
            registry,
            network_name,
            network_handle,
            network_type,
            country_code,
            range_start,
            range_end,
            registered_at,
            updated_at,
            fetched_at,
            expires_at
          ) VALUES (
            $1::cidr, $2, $3, $4, $5, $6, $7::inet, $8::inet,
            $9::timestamptz, $10::timestamptz, now(),
            now() + ($11::bigint * interval '1 millisecond')
          )
          ON CONFLICT (network) DO UPDATE SET
            registry = EXCLUDED.registry,
            network_name = EXCLUDED.network_name,
            network_handle = EXCLUDED.network_handle,
            network_type = EXCLUDED.network_type,
            country_code = EXCLUDED.country_code,
            range_start = EXCLUDED.range_start,
            range_end = EXCLUDED.range_end,
            registered_at = EXCLUDED.registered_at,
            updated_at = EXCLUDED.updated_at,
            fetched_at = EXCLUDED.fetched_at,
            expires_at = EXCLUDED.expires_at
        `,
        [
          network,
          data.registry,
          data.name,
          data.handle,
          data.type,
          data.countryCode,
          data.rangeStart,
          data.rangeEnd,
          data.registeredAt,
          data.updatedAt,
          RDAP_CACHE_TTL_MS,
        ],
      ),
    ),
  );
}

async function fetchRdapData(address: string): Promise<RdapData | null> {
  const version = isIP(address);
  if (version !== 4 && version !== 6) return null;
  const entries = await bootstrapEntries(version);
  const entry = entries.find((candidate) => contains(candidate.cidr, address));
  if (!entry) return null;

  const endpoint = new URL(`ip/${encodeURIComponent(address)}`, entry.baseUrl);
  const response = await fetch(endpoint, {
    cache: "no-store",
    redirect: "error",
    headers: {
      Accept: "application/rdap+json, application/json",
      "User-Agent": "LevikNet-RDAP/1.0",
    },
    signal: AbortSignal.timeout(5_000),
  });
  if (!response.ok) {
    await response.body?.cancel();
    return null;
  }
  const parsed = rdapDataFromResponse(
    await responseJson(response),
    entry.baseUrl,
    address,
  );
  if (!parsed) return null;
  await storeRdapData(parsed.networks, parsed.data).catch(() => {});
  return parsed.data;
}

export async function getRdapData(address: string): Promise<RdapData | null> {
  const fresh = await cachedRdapData(address, true).catch(() => null);
  if (fresh) return fresh;

  globalThis.__leviknetRdapRequests ??= new Map();
  const existing = globalThis.__leviknetRdapRequests.get(address);
  if (existing) return existing;

  const request = fetchRdapData(address)
    .catch(() => cachedRdapData(address, false).catch(() => null))
    .finally(() => {
      globalThis.__leviknetRdapRequests?.delete(address);
    });
  globalThis.__leviknetRdapRequests.set(address, request);
  return request;
}
