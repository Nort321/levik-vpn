"use client";

import { useState } from "react";

import {
  DiagnosticIcon,
  RefreshIcon,
  RouteIcon,
  ShieldCheckIcon,
} from "@/components/icons";
import { ServiceIcon } from "@/components/monitor/service-icon";
import {
  checkServiceFromBrowser,
  submitBrowserChecks,
} from "@/lib/monitor/browser";
import type {
  BrowserServiceResult,
  MonitorService,
} from "@/lib/monitor/types";

type ViewState = BrowserServiceResult["state"] | "idle" | "checking";
type ViewResult = {
  serviceSlug: string;
  state: ViewState;
  latencyMs: number | null;
  reachableChecks: number;
  totalChecks: number;
};

function idleResult(serviceSlug: string): ViewResult {
  return {
    serviceSlug,
    state: "idle",
    latencyMs: null,
    reachableChecks: 0,
    totalChecks: 0,
  };
}

function resultLabel(result: ViewResult): string {
  if (result.state === "idle") return "Не проверено";
  if (result.state === "checking") return "Проверяем";
  if (result.state === "unreachable") return "Нет ответа";
  const latency = result.latencyMs === null ? "" : ` · ${result.latencyMs} мс`;
  if (result.state === "partial") {
    return `Частично · ${result.reachableChecks}/${result.totalChecks}${latency}`;
  }
  return `Доступен${latency}`;
}

export function BrowserDiagnostics({ services }: { services: readonly MonitorService[] }) {
  const [results, setResults] = useState<ViewResult[]>(
    services.map((service) => idleResult(service.slug)),
  );
  const [selected, setSelected] = useState<Set<string>>(
    () => new Set(services.map((service) => service.slug)),
  );
  const [submissionState, setSubmissionState] = useState<"idle" | "saved" | "local">("idle");
  const checking = results.some((result) => result.state === "checking");

  const run = async (targetServices: readonly MonitorService[]) => {
    if (checking || targetServices.length === 0) return;
    const targetSlugs = new Set(targetServices.map((service) => service.slug));
    setSubmissionState("idle");
    setResults((current) => current.map((result) =>
      targetSlugs.has(result.serviceSlug)
        ? { ...idleResult(result.serviceSlug), state: "checking" }
        : result,
    ));
    const next = await Promise.all(targetServices.map(checkServiceFromBrowser));
    setResults((current) => current.map((result) => {
      const measured = next.find((candidate) => candidate.serviceSlug === result.serviceSlug);
      return measured
        ? {
            serviceSlug: measured.serviceSlug,
            state: measured.state,
            latencyMs: measured.latencyMs,
            reachableChecks: measured.checks.filter((check) => check.reachable).length,
            totalChecks: measured.checks.length,
          }
        : result;
    }));
    try {
      await submitBrowserChecks("diagnostic", next);
      setSubmissionState("saved");
    } catch {
      setSubmissionState("local");
    }
  };

  const toggleService = (slug: string) => {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(slug)) next.delete(slug);
      else next.add(slug);
      return next;
    });
  };

  return (
    <section className="monitor-diagnostics" id="my-connection">
      <div>
        <span className="feature-card__icon"><DiagnosticIcon /></span>
        <span className="section-kicker">Проверка из браузера</span>
        <h2>Что открывается именно у вас</h2>
        <p>
          Monitor проверит сайт, API и CDN с вашего подключения. IP используется
          только во время запроса для определения сети и региона, но не сохраняется.
        </p>
        <div className="monitor-diagnostics__actions">
          <button
            className="button button--primary button--large"
            disabled={checking}
            onClick={() => void run(services)}
            type="button"
          >
            {checking ? <RefreshIcon /> : <ShieldCheckIcon />}
            {checking ? "Проверяем…" : "Проверить всё"}
          </button>
        </div>
        <details className="monitor-service-picker">
          <summary><RouteIcon />Выбрать сервисы <span>{selected.size}</span></summary>
          <fieldset disabled={checking}>
            <legend className="visually-hidden">Сервисы для проверки</legend>
            {services.map((service) => (
              <label key={service.slug}>
                <input
                  checked={selected.has(service.slug)}
                  onChange={() => toggleService(service.slug)}
                  type="checkbox"
                />
                <ServiceIcon name={service.name} slug={service.slug} />
                <span>{service.name}</span>
              </label>
            ))}
          </fieldset>
          <button
            className="button button--ghost"
            disabled={checking || selected.size === 0}
            onClick={() => void run(services.filter((service) => selected.has(service.slug)))}
            type="button"
          >
            <DiagnosticIcon />
            Проверить выбранные
          </button>
        </details>
      </div>
      <ul className="browser-results" aria-live="polite">
        {services.map((service) => {
          const result = results.find((candidate) => candidate.serviceSlug === service.slug) ??
            idleResult(service.slug);
          return (
            <li key={service.slug}>
              <span className="browser-result__service">
                <ServiceIcon name={service.name} slug={service.slug} />
                {service.name}
              </span>
              <strong className={`browser-result browser-result--${result.state}`}>
                {resultLabel(result)}
              </strong>
            </li>
          );
        })}
      </ul>
      <p className="monitor-diagnostics__note" aria-live="polite">
        {submissionState === "saved"
          ? "Анонимный результат учтён в пользовательской статистике."
          : submissionState === "local"
            ? "Проверка завершена, но результат показан только вам."
            : "Браузер проверяет доступность endpoint, но не измеряет DNS, TCP и TLS отдельно."}
      </p>
    </section>
  );
}
