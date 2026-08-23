"use client";

import { useState } from "react";

import { AlertIcon, RefreshIcon } from "@/components/icons";
import {
  checkServiceFromBrowser,
  submitBrowserChecks,
} from "@/lib/monitor/browser";
import type { BrowserServiceResult, MonitorService } from "@/lib/monitor/types";

type ReportState = "idle" | "checking" | "confirmed" | "not-confirmed" | "local";

export function ServiceReport({ service }: { service: MonitorService }) {
  const [state, setState] = useState<ReportState>("idle");
  const [result, setResult] = useState<BrowserServiceResult | null>(null);

  const report = async () => {
    if (state === "checking") return;
    setState("checking");
    const next = await checkServiceFromBrowser(service);
    setResult(next);
    try {
      await submitBrowserChecks("report", [next]);
      setState(next.state === "reachable" ? "not-confirmed" : "confirmed");
    } catch {
      setState("local");
    }
  };

  const message = state === "confirmed"
    ? "Проверка обнаружила проблему. Анонимный результат учтён в общей статистике."
    : state === "not-confirmed"
      ? "Сейчас все проверяемые endpoint отвечают. Результат сохранён как обычная проверка."
      : state === "local"
        ? "Диагностика завершена, но результат удалось показать только вам."
        : "Нажатие запустит диагностику; ручной голос без проверки не учитывается.";

  return (
    <div className="service-report">
      <button
        className="button button--danger-ghost button--large"
        disabled={state === "checking"}
        onClick={() => void report()}
        type="button"
      >
        {state === "checking" ? <RefreshIcon /> : <AlertIcon />}
        {state === "checking" ? "Проверяем…" : "У меня не работает"}
      </button>
      <p aria-live="polite">{message}</p>
      {result ? (
        <span>
          Ответили {result.checks.filter((check) => check.reachable).length} из {result.checks.length}
          {result.latencyMs === null ? "" : ` · до ${result.latencyMs} мс`}
        </span>
      ) : null}
    </div>
  );
}
