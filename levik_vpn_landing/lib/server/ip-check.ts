import "server-only";

import { lookup } from "node:dns/promises";
import { isIP } from "node:net";

import { z } from "zod";

import type { IpCheckSnapshot, ProtectionState } from "@/lib/ip-check";
import { getEnvironment } from "@/lib/server/env";
import { getLocalGeoData } from "@/lib/server/geoip";
import { getRdapData } from "@/lib/server/rdap";

const EXIT_CACHE_TTL_MS = 60_000;

const remnawaveExitSchema = z.object({
  response: z
    .array(
      z.object({
        address: z.string().min(1).max(253),
        ips: z
          .array(
            z.object({
              ip: z.string().min(1).max(45),
            }),
          )
          .max(100)
          .optional(),
        configProfile: z
          .object({
            activeInbounds: z
              .array(
                z.object({
                  rawInbound: z.object({
                    listen: z.string().min(1).max(253).optional(),
                  }),
                }),
              )
              .max(200),
          })
          .optional(),
      }),
    )
    .max(500),
});

type ExitCache = { expiresAt: number; addresses: ReadonlySet<string> };

let exitCache: ExitCache | undefined;
let exitRequest: Promise<ReadonlySet<string> | null> | undefined;

function normalizedAddress(value: string): string {
  return value.toLowerCase().replace(/^\[|\]$/g, "");
}

async function resolveAddress(value: string): Promise<string[]> {
  const normalized = normalizedAddress(value);
  if (isIP(normalized) !== 0) return [normalized];

  try {
    const results = await lookup(normalized, { all: true, verbatim: true });
    return results.map((result) => normalizedAddress(result.address));
  } catch {
    return [];
  }
}

async function fetchLevikExitAddresses(): Promise<ReadonlySet<string>> {
  const environment = getEnvironment();
  const upstream = await fetch(environment.REMNAWAVE_STATUS_URL, {
    cache: "no-store",
    redirect: "error",
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${environment.REMNAWAVE_API_TOKEN}`,
    },
    signal: AbortSignal.timeout(8_000),
  });
  if (!upstream.ok) throw new Error("remnawave_exit_upstream_error");

  const parsed = remnawaveExitSchema.safeParse(await upstream.json());
  if (!parsed.success) throw new Error("invalid_remnawave_exit_response");

  const candidates = new Set([
    ...parsed.data.response.map((node) => node.address),
    ...parsed.data.response.flatMap((node) =>
      (node.ips ?? []).map((entry) => entry.ip),
    ),
    ...parsed.data.response.flatMap((node) =>
      (node.configProfile?.activeInbounds ?? []).flatMap((inbound) =>
        inbound.rawInbound.listen ? [inbound.rawInbound.listen] : [],
      ),
    ),
    "leviknet.com",
    "check.leviknet.com",
  ]);
  const resolved = await Promise.all(
    [...candidates].map((candidate) => resolveAddress(candidate)),
  );
  return new Set(resolved.flat());
}

async function getLevikExitAddresses(): Promise<ReadonlySet<string> | null> {
  if (exitCache && exitCache.expiresAt > Date.now()) {
    return exitCache.addresses;
  }

  exitRequest ??= fetchLevikExitAddresses()
    .then((addresses) => {
      exitCache = { addresses, expiresAt: Date.now() + EXIT_CACHE_TTL_MS };
      return addresses;
    })
    .catch(() => exitCache?.addresses ?? null)
    .finally(() => {
      exitRequest = undefined;
    });
  return exitRequest;
}

function protectionState(
  address: string,
  exitAddresses: ReadonlySet<string> | null,
): ProtectionState {
  if (!exitAddresses) return "unknown";
  return exitAddresses.has(normalizedAddress(address)) ? "protected" : "direct";
}

export async function getIpCheckSnapshot(
  address: string,
): Promise<IpCheckSnapshot> {
  const [base, registration, exitAddresses] = await Promise.all([
    getIpCheckBaseSnapshot(address),
    getRdapData(address),
    getLevikExitAddresses(),
  ]);
  const fallbackCountryCode = registration?.countryCode ?? null;
  const fallbackCountry = fallbackCountryCode
    ? new Intl.DisplayNames(["ru"], { type: "region" }).of(
        fallbackCountryCode,
      ) ?? fallbackCountryCode
    : null;
  const fallbackFlag = fallbackCountryCode
    ? [...fallbackCountryCode]
        .map((letter) =>
          String.fromCodePoint(127397 + letter.toUpperCase().charCodeAt(0)),
        )
        .join("")
    : null;

  return {
    ...base,
    country: base.country ?? fallbackCountry,
    countryCode: base.countryCode ?? fallbackCountryCode,
    flagEmoji: base.flagEmoji ?? fallbackFlag,
    provider: base.provider ?? registration?.name ?? null,
    organization: base.organization ?? registration?.name ?? null,
    registration,
    protection: protectionState(address, exitAddresses),
    checkedAt: new Date().toISOString(),
  };
}

export async function getIpCheckBaseSnapshot(
  address: string,
): Promise<IpCheckSnapshot> {
  const version = isIP(address);
  if (version === 0) throw new Error("invalid_client_address");

  const geo = await getLocalGeoData(address);

  return {
    ip: address,
    version: version === 6 ? "IPv6" : "IPv4",
    country: geo?.country ?? null,
    countryCode: geo?.countryCode ?? null,
    region: geo?.region ?? null,
    city: geo?.city ?? null,
    timezone: geo?.timezone ?? null,
    flagEmoji: geo?.flagEmoji ?? null,
    provider: geo?.provider ?? null,
    organization: geo?.organization ?? null,
    asn: geo?.asn ?? null,
    registration: null,
    protection: "unknown",
    checkedAt: new Date().toISOString(),
  };
}
