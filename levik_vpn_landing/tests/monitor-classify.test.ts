import { describe, expect, it } from "vitest";

import { buildOverview, classifyService } from "@/lib/monitor/classify";
import { groupMonitorIncidents } from "@/lib/monitor/incidents";
import type { MonitorService, ProbeMeasurement } from "@/lib/monitor/types";

const service: MonitorService = {
  slug: "discord",
  name: "Discord",
  summary: "Discord",
  host: "discord.com",
  homepageUrl: "https://discord.com/",
  checks: [],
};
const now = new Date("2026-08-15T10:00:00.000Z");

function measurement(
  probeId: string,
  countryCode: string,
  state: ProbeMeasurement["state"],
): ProbeMeasurement {
  return {
    probeId,
    probeLabel: probeId,
    countryCode,
    region: null,
    measuredAt: "2026-08-15T09:59:30.000Z",
    state,
    availability: state === "operational" ? 100 : state === "degraded" ? 70 : 0,
    latencyMs: state === "outage" ? null : 120,
    checks: {},
  };
}

describe("monitor diagnosis", () => {
  it("classifies a Russia-only failure as a probable access restriction", () => {
    const snapshot = classifyService(service, [
      measurement("ru", "RU", "outage"),
      measurement("de", "DE", "operational"),
      measurement("fi", "FI", "operational"),
    ], now);

    expect(snapshot.state).toBe("restricted");
    expect(snapshot.confidence).toBeGreaterThanOrEqual(90);
  });

  it("does not infer a restriction from a single point", () => {
    const snapshot = classifyService(
      service,
      [measurement("ru", "RU", "outage")],
      now,
    );

    expect(snapshot.state).toBe("outage");
    expect(snapshot.diagnosis).not.toContain("ограничен");
  });

  it("ignores stale measurements", () => {
    const stale = {
      ...measurement("de", "DE", "operational"),
      measuredAt: "2026-08-15T09:50:00.000Z",
    };
    expect(classifyService(service, [stale], now).state).toBe("unknown");
  });

  it("separates availability from connection quality and penalizes the index", () => {
    const slow = {
      ...measurement("de", "DE", "degraded"),
      availability: 100,
      latencyMs: 4_200,
    };
    const snapshot = classifyService(service, [slow], now);
    const overview = buildOverview(
      [service],
      new Map([[service.slug, [slow]]]),
      now,
    );

    expect(snapshot.availability).toBe(100);
    expect(snapshot.quality).toBe("reduced");
    expect(snapshot.diagnosis).toContain("качество соединения снижено");
    expect(overview.index).toBe(76);
  });
});

describe("monitor incidents", () => {
  it("groups repeated minute measurements and overlapping probes into one incident", () => {
    const incidents = groupMonitorIncidents([
      { probeId: "de-1", probeLabel: "Frankfurt", measuredAt: "2026-08-15T09:55:00.000Z", state: "degraded", availability: 100, latencyMs: 3_100 },
      { probeId: "de-1", probeLabel: "Frankfurt", measuredAt: "2026-08-15T09:56:00.000Z", state: "degraded", availability: 100, latencyMs: 4_200 },
      { probeId: "nl-1", probeLabel: "Amsterdam", measuredAt: "2026-08-15T09:56:00.000Z", state: "degraded", availability: 80, latencyMs: 2_800 },
      { probeId: "de-1", probeLabel: "Frankfurt", measuredAt: "2026-08-15T09:57:00.000Z", state: "operational", availability: 100, latencyMs: 500 },
      { probeId: "nl-1", probeLabel: "Amsterdam", measuredAt: "2026-08-15T09:58:00.000Z", state: "operational", availability: 100, latencyMs: 600 },
    ], now);

    expect(incidents).toHaveLength(1);
    expect(incidents[0]).toMatchObject({
      startedAt: "2026-08-15T09:55:00.000Z",
      endedAt: "2026-08-15T09:58:00.000Z",
      probeLabels: ["Amsterdam", "Frankfurt"],
      maxLatencyMs: 4_200,
      minAvailability: 80,
    });
  });
});
