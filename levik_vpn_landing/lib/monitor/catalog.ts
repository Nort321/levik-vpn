import rawCatalog from "@/config/monitor-services.json";
import type { MonitorService } from "@/lib/monitor/types";

function normalizeService(service: (typeof rawCatalog)[number]): MonitorService {
  return Object.freeze({
    ...service,
    checks: Object.freeze(service.checks.map((check) => {
      if (check.kind !== "api" && check.kind !== "cdn") {
        throw new Error(`Unknown monitor check kind: ${check.kind}`);
      }
      return Object.freeze({ ...check, kind: check.kind });
    })),
  });
}

export const monitorServices: readonly MonitorService[] = Object.freeze(
  rawCatalog.map(normalizeService),
);

const serviceBySlug = new Map(
  monitorServices.map((service) => [service.slug, service]),
);

export function getMonitorService(slug: string): MonitorService | null {
  return serviceBySlug.get(slug) ?? null;
}
