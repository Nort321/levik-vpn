export type ServerState = "online" | "degraded" | "maintenance" | "offline";

export type PublicServerStatus = {
  id: string;
  countryCode: string;
  state: ServerState;
  load: number | null;
  uptimeSeconds: number | null;
  trafficUsedBytes: number;
  lastStatusChange: string | null;
};

export type StatusSnapshot = {
  servers: PublicServerStatus[];
  fetchedAt: string;
  source: "live" | "stale";
  controlLatencyMs: number;
};
