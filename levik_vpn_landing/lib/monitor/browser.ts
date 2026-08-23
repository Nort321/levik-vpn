import type {
  BrowserEndpointResult,
  BrowserServiceResult,
  MonitorService,
} from "@/lib/monitor/types";

export type BrowserSubmissionMode = "diagnostic" | "report";

type BrowserEndpoint = {
  id: string;
  url: string;
};

async function checkEndpoint(endpoint: BrowserEndpoint): Promise<BrowserEndpointResult> {
  const startedAt = performance.now();
  try {
    const response = await fetch(endpoint.url, {
      method: "GET",
      mode: "no-cors",
      cache: "no-store",
      credentials: "omit",
      redirect: "follow",
      signal: AbortSignal.timeout(8_000),
    });
    await response.body?.cancel();
    return {
      id: endpoint.id,
      reachable: true,
      latencyMs: Math.max(0, Math.round(performance.now() - startedAt)),
    };
  } catch {
    return { id: endpoint.id, reachable: false, latencyMs: null };
  }
}

export async function checkServiceFromBrowser(
  service: MonitorService,
): Promise<BrowserServiceResult> {
  const endpoints: readonly BrowserEndpoint[] = [
    { id: "homepage", url: service.homepageUrl },
    ...service.checks.map((check) => ({ id: check.id, url: check.url })),
  ];
  const checks = await Promise.all(endpoints.map(checkEndpoint));
  const reachable = checks.filter((check) => check.reachable);
  const latencyValues = reachable.flatMap((check) =>
    check.latencyMs === null ? [] : [check.latencyMs],
  );
  return {
    serviceSlug: service.slug,
    state: reachable.length === 0
      ? "unreachable"
      : reachable.length === checks.length
        ? "reachable"
        : "partial",
    latencyMs: latencyValues.length === 0 ? null : Math.max(...latencyValues),
    checks,
  };
}

export async function submitBrowserChecks(
  mode: BrowserSubmissionMode,
  results: readonly BrowserServiceResult[],
): Promise<void> {
  const response = await fetch("/api/monitor/v1/browser-checks", {
    method: "POST",
    cache: "no-store",
    credentials: "omit",
    redirect: "error",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ mode, measuredAt: new Date().toISOString(), results }),
    signal: AbortSignal.timeout(12_000),
  });
  await response.body?.cancel();
  if (!response.ok) throw new Error(`browser_check_http_${response.status}`);
}
