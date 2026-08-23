import type {
  MonitorQuality,
  MonitorOverview,
  MonitorService,
  MonitorServiceSnapshot,
  MonitorState,
  ProbeMeasurement,
} from "@/lib/monitor/types";

const FRESHNESS_WINDOW_MS = 3 * 60_000;

function ratio(values: readonly boolean[]): number {
  if (values.length === 0) return 0;
  return values.filter(Boolean).length / values.length;
}

function isHealthy(measurement: ProbeMeasurement): boolean {
  return measurement.state === "operational";
}

function latencyQualityScore(latencyMs: number): number {
  if (latencyMs <= 1_000) return 100;
  if (latencyMs <= 2_500) return 88;
  if (latencyMs <= 5_000) return 68;
  return 42;
}

function qualityFor(
  state: MonitorState,
  measurements: readonly ProbeMeasurement[],
  availability: number | null,
): { quality: MonitorQuality; qualityScore: number | null } {
  if (state === "unknown" || measurements.length === 0) {
    return { quality: "unknown", qualityScore: null };
  }
  const latencyScores = measurements.flatMap((measurement) =>
    measurement.latencyMs === null
      ? []
      : [latencyQualityScore(measurement.latencyMs)],
  );
  const latencyScore = latencyScores.length === 0
    ? 0
    : Math.round(latencyScores.reduce((sum, score) => sum + score, 0) / latencyScores.length);
  const qualityScore = Math.min(availability ?? 0, latencyScore);
  const quality: MonitorQuality = qualityScore >= 90
    ? "normal"
    : qualityScore >= 60
      ? "reduced"
      : "poor";
  return { quality, qualityScore };
}

function diagnosisFor(
  state: MonitorState,
  measurements: readonly ProbeMeasurement[],
  availability: number | null,
): string {
  if (state === "unknown") {
    return "Пока недостаточно свежих измерений для надёжного вывода.";
  }
  if (state === "restricted") {
    return "Сервис работает с внешних точек, но недоступен из России. Вероятна проблема сети или маршрутизации.";
  }
  if (state === "outage") {
    return "Сбой подтверждается несколькими проверками. Возможна проблема на стороне самого сервиса.";
  }
  if (state === "degraded") {
    if (availability === 100) {
      return "Сервис доступен со всех активных точек, но качество соединения снижено: часть ответов приходит медленно.";
    }
    return "Часть проверок проходит медленно или завершается ошибкой. Доступность зависит от точки подключения.";
  }
  if (measurements.length === 1) {
    return "Сервис отвечает, но для локализации возможной проблемы нужна ещё одна независимая точка.";
  }
  return "Сервис отвечает стабильно со всех активных точек наблюдения.";
}

export function classifyService(
  service: MonitorService,
  measurements: readonly ProbeMeasurement[],
  now = new Date(),
): MonitorServiceSnapshot {
  const cutoff = now.getTime() - FRESHNESS_WINDOW_MS;
  const fresh = measurements.filter(
    (measurement) => new Date(measurement.measuredAt).getTime() >= cutoff,
  );
  const russian = fresh.filter((measurement) => measurement.countryCode === "RU");
  const external = fresh.filter((measurement) => measurement.countryCode !== "RU");
  const russianHealth = ratio(russian.map(isHealthy));
  const externalHealth = ratio(external.map(isHealthy));

  let state: MonitorState = "unknown";
  if (
    russian.length > 0 &&
    external.length > 0 &&
    russianHealth < 0.34 &&
    externalHealth > 0.66
  ) {
    state = "restricted";
  } else if (fresh.length > 0 && fresh.every((measurement) => measurement.state === "outage")) {
    state = "outage";
  } else if (fresh.length > 0 && fresh.every(isHealthy)) {
    state = "operational";
  } else if (fresh.length > 0) {
    state = "degraded";
  }

  const availability = fresh.length === 0
    ? null
    : Math.round(
      fresh.reduce((sum, measurement) => sum + measurement.availability, 0) /
        fresh.length,
    );
  const { quality, qualityScore } = qualityFor(state, fresh, availability);
  const patternStrength = state === "restricted" || state === "outage" ? 12 : 0;
  const confidence = fresh.length === 0
    ? null
    : Math.min(96, 38 + fresh.length * 14 + patternStrength);
  const updatedAt = fresh.reduce<string | null>(
    (latest, measurement) =>
      latest === null || measurement.measuredAt > latest
        ? measurement.measuredAt
        : latest,
    null,
  );

  return {
    service,
    state,
    availability,
    quality,
    qualityScore,
    confidence,
    diagnosis: diagnosisFor(state, fresh, availability),
    updatedAt,
    measurements: fresh,
  };
}

export function buildOverview(
  services: readonly MonitorService[],
  measurements: ReadonlyMap<string, readonly ProbeMeasurement[]>,
  now = new Date(),
): MonitorOverview {
  const snapshots = services.map((service) =>
    classifyService(service, measurements.get(service.slug) ?? [], now),
  );
  const known = snapshots.filter(
    (snapshot): snapshot is MonitorServiceSnapshot & { availability: number } =>
      snapshot.availability !== null,
  );
  const index = known.length === 0
    ? null
    : Math.round(
      known.reduce((sum, snapshot) => {
        const combined = Math.round(
          snapshot.availability * 0.75 + (snapshot.qualityScore ?? 0) * 0.25,
        );
        const score = snapshot.state === "operational"
          ? combined
          : snapshot.state === "degraded"
            ? Math.min(76, combined)
            : snapshot.state === "restricted"
              ? Math.min(55, combined)
              : snapshot.state === "outage"
                ? Math.min(20, combined)
                : 0;
        return sum + score;
      }, 0) /
        known.length,
    );
  const states = snapshots.map((snapshot) => snapshot.state);
  const state: MonitorState = states.includes("outage")
    ? "outage"
    : states.includes("restricted")
      ? "restricted"
      : states.includes("degraded")
        ? "degraded"
        : known.length > 0
          ? "operational"
          : "unknown";
  const updatedAt = snapshots.reduce<string | null>(
    (latest, snapshot) =>
      snapshot.updatedAt !== null && (latest === null || snapshot.updatedAt > latest)
        ? snapshot.updatedAt
        : latest,
    null,
  );
  const probes = new Set(
    snapshots.flatMap((snapshot) =>
      snapshot.measurements.map((measurement) => measurement.probeId),
    ),
  );

  return {
    index,
    state,
    updatedAt,
    activeProbeCount: probes.size,
    services: snapshots,
  };
}
