import Link from "next/link";

import { ArrowUpRightIcon, GlobeIcon, RouteIcon, ShieldCheckIcon } from "@/components/icons";
import { MonitorStateBadge } from "@/components/monitor/monitor-state";
import { ServiceIcon } from "@/components/monitor/service-icon";
import { ServiceReport } from "@/components/monitor/service-report";
import { UserCheckMap } from "@/components/monitor/user-check-map";
import type {
  MonitorHistoryPoint,
  MonitorIncident,
  MonitorServiceSnapshot,
  MonitorUserSignals,
} from "@/lib/monitor/types";

type CheckView = { id: string; label: string; ok: boolean | null; latencyMs: number | null; status: number | null };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function checkView(id: string, label: string, value: unknown): CheckView {
  if (!isRecord(value)) return { id, label, ok: null, latencyMs: null, status: null };
  return {
    id,
    label,
    ok: typeof value.ok === "boolean" ? value.ok : null,
    latencyMs: typeof value.latencyMs === "number" ? value.latencyMs : null,
    status: typeof value.status === "number" ? value.status : null,
  };
}

function measurementChecks(snapshot: MonitorServiceSnapshot, checks: Record<string, unknown>): readonly CheckView[] {
  const labels = new Map<string, string>([
    ["dns", "DNS"], ["tcp", "TCP 443"], ["tls", "TLS handshake"], ["http", "Главная страница"],
    ...snapshot.service.checks.map((check) => [check.id, check.label] as const),
  ]);
  return [...labels].map(([id, label]) => checkView(id, label, checks[id]));
}

function eventTime(value: string): string {
  return new Intl.DateTimeFormat("ru-RU", {
    hour: "2-digit", minute: "2-digit", timeZone: "Europe/Moscow",
  }).format(new Date(value));
}

function incidentPeriod(incident: MonitorIncident): string {
  const start = eventTime(incident.startedAt);
  if (!incident.endedAt) return `${start} — сейчас`;
  return `${start}–${eventTime(incident.endedAt)}`;
}

function qualityLabel(snapshot: MonitorServiceSnapshot): string {
  if (snapshot.quality === "normal") return "Нормальное";
  if (snapshot.quality === "reduced") return "Снижено";
  if (snapshot.quality === "poor") return "Плохое";
  return "Нет данных";
}

function userSignalState(successRate: number): "ok" | "warning" | "error" {
  if (successRate >= 95) return "ok";
  if (successRate >= 75) return "warning";
  return "error";
}

export function ServiceDetail({
  snapshot,
  history,
  incidents,
  userSignals,
}: {
  snapshot: MonitorServiceSnapshot;
  history: readonly MonitorHistoryPoint[];
  incidents: readonly MonitorIncident[];
  userSignals: MonitorUserSignals;
}) {
  const workingThroughLevik = snapshot.measurements.some((measurement) => measurement.state === "operational");
  const observationCount = snapshot.measurements.reduce((total, measurement) =>
    total + Object.keys(measurement.checks).length, 0);

  return (
    <>
      <section className="container service-detail-hero">
        <Link className="monitor-back-link" href="https://mon.leviknet.com/">← Интернет-пульс</Link>
        <div className="service-detail-hero__grid">
          <div>
            <ServiceIcon
              className="service-detail__mark"
              name={snapshot.service.name}
              slug={snapshot.service.slug}
            />
            <p className="eyebrow"><span aria-hidden="true" className="live-dot" />Статус обновляется каждую минуту</p>
            <h1>{snapshot.service.name} не работает?</h1>
            <MonitorStateBadge state={snapshot.state} />
            <p className="service-detail-hero__diagnosis">{snapshot.diagnosis}</p>
            <ServiceReport service={snapshot.service} />
          </div>
          <article className="service-confidence-card">
            <span>Уверенность диагноза</span>
            <strong>{snapshot.confidence === null ? "—" : `${snapshot.confidence}%`}</strong>
            <div><i style={{ width: `${snapshot.confidence ?? 0}%` }} /></div>
            <dl>
              <div><dt>Свежих точек</dt><dd>{snapshot.measurements.length}</dd></div>
              <div><dt>Проверок</dt><dd>{observationCount}</dd></div>
              <div><dt>Доступность</dt><dd>{snapshot.availability === null ? "—" : `${snapshot.availability}%`}</dd></div>
              <div><dt>Качество</dt><dd>{qualityLabel(snapshot)}</dd></div>
            </dl>
          </article>
        </div>
      </section>

      <section className="landing-section service-probes-section">
        <div className="container">
          <div className="status-section-heading">
            <div><span>Серверная диагностика</span><h2>Точки Levik</h2></div>
            <p>DNS, TCP, TLS, HTTP, API и CDN проверяются независимо через сетевой маршрут каждой точки.</p>
          </div>
          {snapshot.measurements.length === 0 ? (
            <div className="monitor-empty"><GlobeIcon /><h3>Датчики подключаются</h3><p>Первые реальные результаты появятся после следующего цикла измерений.</p></div>
          ) : (
            <div className="service-probes-grid">
              {snapshot.measurements.map((measurement) => (
                <article className="service-probe" key={measurement.probeId}>
                  <div className="service-probe__head">
                    <div><span>{measurement.countryCode}</span><h3>{measurement.probeLabel}</h3><p>{measurement.region ?? "Региональная точка Levik"}</p></div>
                    <MonitorStateBadge state={measurement.state} />
                  </div>
                  <div className="service-check-list">
                    {measurementChecks(snapshot, measurement.checks).map((check) => (
                      <div key={check.id}>
                        <span className={`service-check-dot service-check-dot--${check.ok === true ? "ok" : check.ok === false ? "error" : "unknown"}`} />
                        <strong>{check.label}</strong>
                        <small>{check.status ? `HTTP ${check.status} · ` : ""}{check.latencyMs === null ? "нет данных" : `${check.latencyMs} мс`}</small>
                      </div>
                    ))}
                  </div>
                </article>
              ))}
            </div>
          )}
        </div>
      </section>

      <section className="landing-section monitor-user-signals-section">
        <div className="container">
          <div className="status-section-heading">
            <div><span>Пользовательский слой</span><h2>Проверки с реальных подключений</h2></div>
            <p>Эти результаты не смешиваются с серверными точками Levik и показываются только при достаточной выборке.</p>
          </div>
          <div className="monitor-user-signals">
            <article className="glow-panel monitor-user-summary">
              <span>Последние {userSignals.windowMinutes} минут</span>
              {userSignals.sufficientData ? (
                <>
                  <strong>{userSignals.successRate}%</strong>
                  <h3>проверок завершились успешно</h3>
                  <dl>
                    <div><dt>Всего проверок</dt><dd>{userSignals.totalChecks}</dd></div>
                    <div><dt>Успешных</dt><dd>{userSignals.totalChecks - userSignals.failedChecks}</dd></div>
                    <div><dt>С ошибками</dt><dd>{userSignals.failedChecks}</dd></div>
                    <div><dt>Подтверждённых репортов</dt><dd>{userSignals.confirmedReports}</dd></div>
                  </dl>
                </>
              ) : (
                <>
                  <strong>—</strong>
                  <h3>Недостаточно пользовательских данных</h3>
                  <p>Нужно минимум 10 свежих проверок. Сейчас получено: {userSignals.totalChecks}.</p>
                </>
              )}
            </article>
            <article className="glow-panel monitor-network-summary">
              <span>Сети и провайдеры</span>
              <h3>Только группы от 10 проверок</h3>
              {userSignals.networks.length === 0 ? (
                <p>Пока ни по одной сети нет достаточной выборки для корректного сравнения.</p>
              ) : (
                <ul>
                  {userSignals.networks.map((network) => (
                    <li key={`${network.asn ?? "unknown"}-${network.region ?? "all"}`}>
                      <span
                        aria-hidden="true"
                        className={`monitor-signal-dot monitor-signal-dot--${userSignalState(network.successRate)}`}
                      />
                      <div><strong>{network.provider}</strong><span>{network.asn ? `AS${network.asn}` : "ASN не определён"}</span></div>
                      <b>{network.successRate}%</b>
                      <small>{network.totalChecks} проверок</small>
                    </li>
                  ))}
                </ul>
              )}
            </article>
            <UserCheckMap countries={userSignals.countries} serviceName={snapshot.service.name} />
          </div>
        </div>
      </section>

      <section className="landing-section service-history-section">
        <div className="container service-history-grid">
          <article className="glow-panel monitor-history-card">
            <span className="section-kicker">Последние 24 часа</span>
            <h2>Доступность {snapshot.service.name}</h2>
            {history.length === 0 ? <p className="monitor-muted">История накопится после запуска наблюдения.</p> : (
              <div className="monitor-history-chart" aria-label="График доступности за 24 часа">
                {history.map((point) => <i key={point.at} style={{ height: `${Math.max(4, point.availability)}%` }} title={`${eventTime(point.at)} — ${point.availability}%`} />)}
              </div>
            )}
          </article>
          <article className="glow-panel monitor-events-card">
            <span className="section-kicker">Инциденты</span>
            <h2>Что менялось</h2>
            {incidents.length === 0 ? <p className="monitor-muted">За последние 24 часа заметных отклонений не записано.</p> : (
              <ol>{incidents.map((incident) => (
                <li key={`${incident.startedAt}-${incident.probeLabels.join("-")}`}>
                  <time>{incidentPeriod(incident)}</time>
                  <span className={`service-check-dot service-check-dot--${incident.state === "outage" ? "error" : "unknown"}`} />
                  <div>
                    <strong>{incident.endedAt ? "Работа восстановлена" : incident.state === "outage" ? "Недоступность" : "Повышенная задержка"}</strong>
                    <p>{incident.probeLabels.join(" / ")}{incident.maxLatencyMs === null ? "" : ` · максимум ${(incident.maxLatencyMs / 1_000).toFixed(2)} сек`}</p>
                  </div>
                </li>
              ))}</ol>
            )}
          </article>
        </div>
      </section>

      <section className="service-route-cta">
        <div className="container service-route-cta__inner">
          <span className="feature-card__icon"><RouteIcon /></span>
          <div>
            <span className="section-kicker">Альтернативный маршрут</span>
            <h2>{workingThroughLevik ? `С активных точек Levik ${snapshot.service.name} отвечает` : "Проверьте подключение через другой маршрут"}</h2>
            <p>Monitor бесплатен и не требует подписки. Levik VPN — отдельный способ изменить сетевой маршрут, если проблема локальная.</p>
          </div>
          <Link className="button button--primary button--large" href="https://leviknet.com/">
            <ShieldCheckIcon />
            Открыть Levik VPN
            <ArrowUpRightIcon />
          </Link>
        </div>
      </section>
    </>
  );
}
