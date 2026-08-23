import "server-only";

import type { QueryResultRow } from "pg";

import { monitorServices } from "@/lib/monitor/catalog";
import { buildOverview, classifyService } from "@/lib/monitor/classify";
import { groupMonitorIncidents } from "@/lib/monitor/incidents";
import type {
  MonitorHistoryPoint,
  MonitorIncident,
  MonitorOverview,
  MonitorServiceSnapshot,
  MonitorUserSignals,
  ProbeMeasurement,
  ProbeState,
} from "@/lib/monitor/types";
import { query } from "@/lib/server/db";

type MeasurementRow = QueryResultRow & {
  service_slug: string;
  probe_id: string;
  probe_label: string;
  country_code: string;
  region: string | null;
  measured_at: Date;
  state: ProbeState;
  availability: number;
  latency_ms: number | null;
  checks: Record<string, unknown>;
};

type HistoryRow = QueryResultRow & {
  bucket: Date;
  availability: number;
  latency_ms: number | null;
};

type EventRow = QueryResultRow & {
  probe_id: string;
  measured_at: Date;
  state: ProbeState;
  probe_label: string;
  availability: number;
  latency_ms: number | null;
};

type UserSignalTotalsRow = QueryResultRow & {
  total_checks: number;
  failed_checks: number;
  confirmed_reports: number;
  updated_at: Date | null;
};

type UserNetworkRow = QueryResultRow & {
  asn: string | null;
  provider: string | null;
  region: string | null;
  total_checks: number;
  failed_checks: number;
};

type UserCountryRow = QueryResultRow & {
  country_code: string;
  total_checks: number;
  failed_checks: number;
};

function toMeasurement(row: MeasurementRow): ProbeMeasurement {
  return {
    probeId: row.probe_id,
    probeLabel: row.probe_label,
    countryCode: row.country_code,
    region: row.region,
    measuredAt: row.measured_at.toISOString(),
    state: row.state,
    availability: row.availability,
    latencyMs: row.latency_ms,
    checks: row.checks,
  };
}

async function getLatestMeasurements(): Promise<readonly MeasurementRow[]> {
  const result = await query<MeasurementRow>(`
    SELECT DISTINCT ON (measurement.service_slug, probe.id)
      measurement.service_slug,
      probe.id AS probe_id,
      probe.label AS probe_label,
      probe.country_code,
      probe.region,
      batch.measured_at,
      measurement.state,
      measurement.availability,
      measurement.latency_ms,
      measurement.checks
    FROM monitor_measurements AS measurement
    INNER JOIN monitor_batches AS batch ON batch.batch_id = measurement.batch_id
    INNER JOIN monitor_probes AS probe ON probe.id = batch.probe_id
    WHERE batch.measured_at >= now() - interval '10 minutes'
    ORDER BY measurement.service_slug, probe.id, batch.measured_at DESC
  `);
  return result.rows;
}

export async function getMonitorOverview(): Promise<MonitorOverview> {
  const rows = await getLatestMeasurements();
  const grouped = new Map<string, ProbeMeasurement[]>();
  for (const row of rows) {
    const measurements = grouped.get(row.service_slug) ?? [];
    measurements.push(toMeasurement(row));
    grouped.set(row.service_slug, measurements);
  }
  return buildOverview(monitorServices, grouped);
}

export async function getMonitorServiceSnapshot(
  slug: string,
): Promise<MonitorServiceSnapshot | null> {
  const service = monitorServices.find((candidate) => candidate.slug === slug);
  if (!service) return null;
  const rows = (await getLatestMeasurements()).filter(
    (row) => row.service_slug === slug,
  );
  return classifyService(service, rows.map(toMeasurement));
}

export async function getMonitorHistory(
  slug: string,
): Promise<readonly MonitorHistoryPoint[]> {
  const result = await query<HistoryRow>(
    `
      SELECT
        date_trunc('hour', batch.measured_at) AS bucket,
        round(avg(measurement.availability))::integer AS availability,
        round(avg(measurement.latency_ms))::integer AS latency_ms
      FROM monitor_measurements AS measurement
      INNER JOIN monitor_batches AS batch ON batch.batch_id = measurement.batch_id
      WHERE measurement.service_slug = $1
        AND batch.measured_at >= now() - interval '24 hours'
      GROUP BY bucket
      ORDER BY bucket ASC
    `,
    [slug],
  );
  return result.rows.map((row) => ({
    at: row.bucket.toISOString(),
    availability: row.availability,
    latencyMs: row.latency_ms,
  }));
}

export async function getMonitorIncidents(
  slug: string,
): Promise<readonly MonitorIncident[]> {
  const result = await query<EventRow>(
    `
      SELECT
        probe.id AS probe_id,
        probe.label AS probe_label,
        batch.measured_at,
        measurement.state,
        measurement.availability,
        measurement.latency_ms
      FROM monitor_measurements AS measurement
      INNER JOIN monitor_batches AS batch ON batch.batch_id = measurement.batch_id
      INNER JOIN monitor_probes AS probe ON probe.id = batch.probe_id
      WHERE measurement.service_slug = $1
        AND batch.measured_at >= now() - interval '24 hours'
      ORDER BY batch.measured_at ASC
    `,
    [slug],
  );
  return groupMonitorIncidents(result.rows.map((row) => ({
    probeId: row.probe_id,
    probeLabel: row.probe_label,
    measuredAt: row.measured_at.toISOString(),
    state: row.state,
    availability: row.availability,
    latencyMs: row.latency_ms,
  }))).slice(0, 8);
}

export async function getMonitorUserSignals(
  slug: string,
): Promise<MonitorUserSignals> {
  const [totalsResult, countriesResult, networksResult] = await Promise.all([
    query<UserSignalTotalsRow>(
      `
        SELECT
          count(*)::integer AS total_checks,
          (count(*) FILTER (WHERE state <> 'reachable'))::integer AS failed_checks,
          (count(*) FILTER (
            WHERE mode = 'report' AND state <> 'reachable'
          ))::integer AS confirmed_reports,
          max(received_at) AS updated_at
        FROM monitor_browser_checks
        WHERE service_slug = $1
          AND received_at >= now() - interval '15 minutes'
      `,
      [slug],
    ),
    query<UserCountryRow>(
      `
        SELECT
          country_code,
          count(*)::integer AS total_checks,
          (count(*) FILTER (WHERE state <> 'reachable'))::integer AS failed_checks
        FROM monitor_browser_checks
        WHERE service_slug = $1
          AND received_at >= now() - interval '15 minutes'
          AND country_code IS NOT NULL
        GROUP BY country_code
        HAVING count(*) >= 10
        ORDER BY count(*) DESC
        LIMIT 16
      `,
      [slug],
    ),
    query<UserNetworkRow>(
      `
        SELECT
          asn,
          provider,
          NULL::text AS region,
          count(*)::integer AS total_checks,
          (count(*) FILTER (WHERE state <> 'reachable'))::integer AS failed_checks
        FROM monitor_browser_checks
        WHERE service_slug = $1
          AND received_at >= now() - interval '15 minutes'
          AND asn IS NOT NULL
        GROUP BY asn, provider
        HAVING count(*) >= 10
        ORDER BY count(*) DESC
        LIMIT 8
      `,
      [slug],
    ),
  ]);
  const totals = totalsResult.rows[0];
  const totalChecks = totals?.total_checks ?? 0;
  const failedChecks = totals?.failed_checks ?? 0;
  const sufficientData = totalChecks >= 10;
  return {
    windowMinutes: 15,
    totalChecks,
    failedChecks,
    successRate: sufficientData
      ? Math.round(((totalChecks - failedChecks) / totalChecks) * 100)
      : null,
    confirmedReports: totals?.confirmed_reports ?? 0,
    sufficientData,
    updatedAt: totals?.updated_at?.toISOString() ?? null,
    countries: countriesResult.rows.map((row) => ({
      countryCode: row.country_code,
      totalChecks: row.total_checks,
      failedChecks: row.failed_checks,
      successRate: Math.round(
        ((row.total_checks - row.failed_checks) / row.total_checks) * 100,
      ),
    })),
    networks: networksResult.rows.map((row) => ({
      asn: row.asn === null ? null : Number(row.asn),
      provider: row.provider ?? (row.asn === null ? "Неизвестная сеть" : `AS${row.asn}`),
      region: row.region,
      totalChecks: row.total_checks,
      failedChecks: row.failed_checks,
      successRate: Math.round(
        ((row.total_checks - row.failed_checks) / row.total_checks) * 100,
      ),
    })),
  };
}
