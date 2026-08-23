import type { MonitorIncident, ProbeState } from "@/lib/monitor/types";

const ACTIVE_WINDOW_MS = 3 * 60_000;
const MERGE_GAP_MS = 2 * 60_000;

export type IncidentMeasurement = {
  probeId: string;
  probeLabel: string;
  measuredAt: string;
  state: ProbeState;
  availability: number;
  latencyMs: number | null;
};

type MutableIncident = {
  startedAt: string;
  endedAt: string | null;
  lastFailureAt: string;
  state: ProbeState;
  probeLabels: Set<string>;
  maxLatencyMs: number | null;
  minAvailability: number;
};

function severity(state: ProbeState): number {
  return state === "outage" ? 2 : state === "degraded" ? 1 : 0;
}

function updateIncident(
  incident: MutableIncident,
  measurement: IncidentMeasurement,
): void {
  incident.lastFailureAt = measurement.measuredAt;
  incident.minAvailability = Math.min(incident.minAvailability, measurement.availability);
  if (severity(measurement.state) > severity(incident.state)) {
    incident.state = measurement.state;
  }
  if (
    measurement.latencyMs !== null &&
    (incident.maxLatencyMs === null || measurement.latencyMs > incident.maxLatencyMs)
  ) {
    incident.maxLatencyMs = measurement.latencyMs;
  }
}

export function groupMonitorIncidents(
  measurements: readonly IncidentMeasurement[],
  now = new Date(),
): readonly MonitorIncident[] {
  const byProbe = new Map<string, IncidentMeasurement[]>();
  for (const measurement of measurements) {
    const probeMeasurements = byProbe.get(measurement.probeId) ?? [];
    probeMeasurements.push(measurement);
    byProbe.set(measurement.probeId, probeMeasurements);
  }

  const episodes: MutableIncident[] = [];
  for (const probeMeasurements of byProbe.values()) {
    probeMeasurements.sort((left, right) => left.measuredAt.localeCompare(right.measuredAt));
    let current: MutableIncident | null = null;
    for (const measurement of probeMeasurements) {
      if (measurement.state === "operational") {
        if (current) {
          current.endedAt = measurement.measuredAt;
          episodes.push(current);
          current = null;
        }
        continue;
      }
      if (!current) {
        current = {
          startedAt: measurement.measuredAt,
          endedAt: null,
          lastFailureAt: measurement.measuredAt,
          state: measurement.state,
          probeLabels: new Set([measurement.probeLabel]),
          maxLatencyMs: measurement.latencyMs,
          minAvailability: measurement.availability,
        };
      } else {
        updateIncident(current, measurement);
      }
    }
    if (current) {
      if (now.getTime() - new Date(current.lastFailureAt).getTime() > ACTIVE_WINDOW_MS) {
        current.endedAt = current.lastFailureAt;
      }
      episodes.push(current);
    }
  }

  episodes.sort((left, right) => left.startedAt.localeCompare(right.startedAt));
  const merged: MutableIncident[] = [];
  for (const episode of episodes) {
    const previous = merged.at(-1);
    const previousEnd = previous?.endedAt ?? now.toISOString();
    if (
      previous &&
      new Date(episode.startedAt).getTime() <= new Date(previousEnd).getTime() + MERGE_GAP_MS
    ) {
      previous.endedAt = previous.endedAt === null || episode.endedAt === null
        ? null
        : previous.endedAt > episode.endedAt
          ? previous.endedAt
          : episode.endedAt;
      previous.lastFailureAt = previous.lastFailureAt > episode.lastFailureAt
        ? previous.lastFailureAt
        : episode.lastFailureAt;
      previous.minAvailability = Math.min(previous.minAvailability, episode.minAvailability);
      previous.maxLatencyMs = previous.maxLatencyMs === null
        ? episode.maxLatencyMs
        : episode.maxLatencyMs === null
          ? previous.maxLatencyMs
          : Math.max(previous.maxLatencyMs, episode.maxLatencyMs);
      if (severity(episode.state) > severity(previous.state)) previous.state = episode.state;
      for (const label of episode.probeLabels) previous.probeLabels.add(label);
    } else {
      merged.push({ ...episode, probeLabels: new Set(episode.probeLabels) });
    }
  }

  return merged
    .map((incident) => ({
      startedAt: incident.startedAt,
      endedAt: incident.endedAt,
      state: incident.state,
      probeLabels: [...incident.probeLabels].sort(),
      maxLatencyMs: incident.maxLatencyMs,
      minAvailability: incident.minAvailability,
    }))
    .sort((left, right) => right.startedAt.localeCompare(left.startedAt));
}
