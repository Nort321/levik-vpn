import type { MonitorState } from "@/lib/monitor/types";

const stateLabels: Record<MonitorState, string> = {
  operational: "Работает",
  degraded: "Деградация",
  outage: "Недоступен",
  restricted: "Ограничение доступа",
  unknown: "Собираем данные",
};

export function monitorStateLabel(state: MonitorState): string {
  return stateLabels[state];
}

export function MonitorStateBadge({ state }: { state: MonitorState }) {
  return (
    <span className={`monitor-state monitor-state--${state}`}>
      <span aria-hidden="true" />
      {stateLabels[state]}
    </span>
  );
}
