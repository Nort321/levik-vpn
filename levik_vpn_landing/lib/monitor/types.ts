export type ProbeState = "operational" | "degraded" | "outage";

export type MonitorState = ProbeState | "restricted" | "unknown";

export type MonitorQuality = "normal" | "reduced" | "poor" | "unknown";

export type MonitorCheckKind = "api" | "cdn";

export type MonitorServiceCheck = {
  id: string;
  label: string;
  url: string;
  kind: MonitorCheckKind;
};

export type MonitorService = {
  slug: string;
  name: string;
  summary: string;
  host: string;
  homepageUrl: string;
  checks: readonly MonitorServiceCheck[];
};

export type ProbeMeasurement = {
  probeId: string;
  probeLabel: string;
  countryCode: string;
  region: string | null;
  measuredAt: string;
  state: ProbeState;
  availability: number;
  latencyMs: number | null;
  checks: Record<string, unknown>;
};

export type MonitorServiceSnapshot = {
  service: MonitorService;
  state: MonitorState;
  availability: number | null;
  quality: MonitorQuality;
  qualityScore: number | null;
  confidence: number | null;
  diagnosis: string;
  updatedAt: string | null;
  measurements: readonly ProbeMeasurement[];
};

export type MonitorOverview = {
  index: number | null;
  state: MonitorState;
  updatedAt: string | null;
  activeProbeCount: number;
  services: readonly MonitorServiceSnapshot[];
};

export type MonitorHistoryPoint = {
  at: string;
  availability: number;
  latencyMs: number | null;
};

export type MonitorIncident = {
  startedAt: string;
  endedAt: string | null;
  state: ProbeState;
  probeLabels: readonly string[];
  maxLatencyMs: number | null;
  minAvailability: number;
};

export type BrowserCheckState = "reachable" | "partial" | "unreachable";

export type BrowserEndpointResult = {
  id: string;
  reachable: boolean;
  latencyMs: number | null;
};

export type BrowserServiceResult = {
  serviceSlug: string;
  state: BrowserCheckState;
  latencyMs: number | null;
  checks: readonly BrowserEndpointResult[];
};

export type MonitorUserNetworkSummary = {
  asn: number | null;
  provider: string;
  region: string | null;
  totalChecks: number;
  failedChecks: number;
  successRate: number;
};

export type MonitorUserCountrySummary = {
  countryCode: string;
  totalChecks: number;
  failedChecks: number;
  successRate: number;
};

export type MonitorUserSignals = {
  windowMinutes: number;
  totalChecks: number;
  failedChecks: number;
  successRate: number | null;
  confirmedReports: number;
  sufficientData: boolean;
  updatedAt: string | null;
  countries: readonly MonitorUserCountrySummary[];
  networks: readonly MonitorUserNetworkSummary[];
};
