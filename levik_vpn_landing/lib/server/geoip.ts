import "server-only";

import path from "node:path";
import { stat } from "node:fs/promises";

import maxmind, { type Reader } from "maxmind";
import tzLookup from "tz-lookup";
import { z } from "zod";

const DATABASE_DIRECTORY = "/var/lib/leviknet/geoip";
const READER_REFRESH_INTERVAL_MS = 60_000;

const localizedNamesSchema = z.record(z.string(), z.string().max(160));
const cityRecordSchema = z.object({
  country: z
    .object({
      iso_code: z.string().regex(/^[A-Za-z]{2}$/).optional(),
      names: localizedNamesSchema.optional(),
    })
    .optional(),
  city: z.object({ names: localizedNamesSchema.optional() }).optional(),
  subdivisions: z
    .array(z.object({ names: localizedNamesSchema.optional() }))
    .max(10)
    .optional(),
  location: z
    .object({
      latitude: z.number().min(-90).max(90).optional(),
      longitude: z.number().min(-180).max(180).optional(),
    })
    .optional(),
});
const asnRecordSchema = z.object({
  autonomous_system_number: z.number().int().positive().optional(),
  autonomous_system_organization: z.string().max(240).optional(),
});

type MmdbRecord = Record<string, unknown>;
type ReaderState = {
  city: Reader<MmdbRecord>;
  asn: Reader<MmdbRecord>;
  cityMtimeMs: number;
  asnMtimeMs: number;
};

declare global {
  var __leviknetGeoIpReaders: ReaderState | undefined;
  var __leviknetGeoIpReaderCheckAt: number | undefined;
  var __leviknetGeoIpReaderRequest: Promise<ReaderState | null> | undefined;
}

export type LocalGeoData = {
  country: string | null;
  countryCode: string | null;
  region: string | null;
  city: string | null;
  timezone: string | null;
  flagEmoji: string | null;
  provider: string | null;
  organization: string | null;
  asn: number | null;
};

function localizedName(
  names: Record<string, string> | undefined,
): string | null {
  return names?.ru ?? names?.en ?? Object.values(names ?? {})[0] ?? null;
}

function countryName(code: string, names?: Record<string, string>): string {
  const fromDatabase = localizedName(names);
  if (fromDatabase) return fromDatabase;
  return new Intl.DisplayNames(["ru"], { type: "region" }).of(code) ?? code;
}

function flagEmoji(code: string): string {
  return [...code.toUpperCase()]
    .map((letter) => String.fromCodePoint(127397 + letter.charCodeAt(0)))
    .join("");
}

async function openReaders(): Promise<ReaderState> {
  const cityPath = path.join(DATABASE_DIRECTORY, "dbip-city-lite.mmdb");
  const asnPath = path.join(DATABASE_DIRECTORY, "dbip-asn-lite.mmdb");
  const [cityStat, asnStat, city, asn] = await Promise.all([
    stat(cityPath),
    stat(asnPath),
    maxmind.open<MmdbRecord>(cityPath),
    maxmind.open<MmdbRecord>(asnPath),
  ]);
  return {
    city,
    asn,
    cityMtimeMs: cityStat.mtimeMs,
    asnMtimeMs: asnStat.mtimeMs,
  };
}

async function getReaders(): Promise<ReaderState | null> {
  const now = Date.now();
  if (
    globalThis.__leviknetGeoIpReaders &&
    (globalThis.__leviknetGeoIpReaderCheckAt ?? 0) > now
  ) {
    return globalThis.__leviknetGeoIpReaders;
  }

  globalThis.__leviknetGeoIpReaderRequest ??= (async () => {
    globalThis.__leviknetGeoIpReaderCheckAt =
      now + READER_REFRESH_INTERVAL_MS;
    try {
      const cityPath = path.join(DATABASE_DIRECTORY, "dbip-city-lite.mmdb");
      const asnPath = path.join(DATABASE_DIRECTORY, "dbip-asn-lite.mmdb");
      const [cityStat, asnStat] = await Promise.all([
        stat(cityPath),
        stat(asnPath),
      ]);
      const current = globalThis.__leviknetGeoIpReaders;
      if (
        current &&
        current.cityMtimeMs === cityStat.mtimeMs &&
        current.asnMtimeMs === asnStat.mtimeMs
      ) {
        return current;
      }
      const readers = await openReaders();
      globalThis.__leviknetGeoIpReaders = readers;
      return readers;
    } catch {
      return globalThis.__leviknetGeoIpReaders ?? null;
    }
  })().finally(() => {
    globalThis.__leviknetGeoIpReaderRequest = undefined;
  });
  return globalThis.__leviknetGeoIpReaderRequest;
}

export function geoDataFromRecords(
  cityValue: unknown,
  asnValue: unknown,
): LocalGeoData | null {
  const city = cityRecordSchema.safeParse(cityValue);
  const asn = asnRecordSchema.safeParse(asnValue);
  if (!city.success && !asn.success) return null;

  const countryCode = city.success
    ? city.data.country?.iso_code?.toUpperCase() ?? null
    : null;
  const latitude = city.success ? city.data.location?.latitude : undefined;
  const longitude = city.success ? city.data.location?.longitude : undefined;
  let timezone: string | null = null;
  if (latitude !== undefined && longitude !== undefined) {
    try {
      timezone = tzLookup(latitude, longitude);
    } catch {
      timezone = null;
    }
  }
  const organization = asn.success
    ? asn.data.autonomous_system_organization ?? null
    : null;

  return {
    country:
      countryCode && city.success
        ? countryName(countryCode, city.data.country?.names)
        : null,
    countryCode,
    region: city.success
      ? localizedName(city.data.subdivisions?.[0]?.names)
      : null,
    city: city.success ? localizedName(city.data.city?.names) : null,
    timezone,
    flagEmoji: countryCode ? flagEmoji(countryCode) : null,
    provider: organization,
    organization,
    asn: asn.success ? asn.data.autonomous_system_number ?? null : null,
  };
}

export async function getLocalGeoData(
  address: string,
): Promise<LocalGeoData | null> {
  const readers = await getReaders();
  if (!readers) return null;
  return geoDataFromRecords(readers.city.get(address), readers.asn.get(address));
}
