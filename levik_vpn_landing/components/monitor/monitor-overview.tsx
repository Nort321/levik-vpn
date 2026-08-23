import Link from "next/link";

import { ArrowUpRightIcon, GlobeIcon, PulseIcon, RouteIcon } from "@/components/icons";
import { BrowserDiagnostics } from "@/components/monitor/browser-diagnostics";
import { MonitorStateBadge } from "@/components/monitor/monitor-state";
import { ServiceIcon } from "@/components/monitor/service-icon";
import type { MonitorOverview } from "@/lib/monitor/types";

function updatedLabel(value: string | null): string {
  if (!value) return "Ожидаем первые измерения";
  return `Обновлено в ${new Intl.DateTimeFormat("ru-RU", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    timeZone: "Europe/Moscow",
  }).format(new Date(value))} МСК`;
}

function pulseDescription(overview: MonitorOverview): string {
  if (overview.index === null) return "Датчики подключаются к системе наблюдения";
  if (overview.state === "operational") return "Основные сервисы работают стабильно";
  if (overview.state === "restricted") return "Есть признаки региональных ограничений";
  if (overview.state === "outage") return "Зафиксирована массовая недоступность";
  return "Наблюдаются локальные проблемы";
}

function qualityLabel(quality: MonitorOverview["services"][number]["quality"]): string {
  if (quality === "normal") return "нормальное";
  if (quality === "reduced") return "снижено";
  if (quality === "poor") return "плохое";
  return "нет данных";
}

export function MonitorOverviewView({ overview }: { overview: MonitorOverview }) {
  return (
    <>
      <section className="container monitor-hero">
        <div className="monitor-hero__copy">
          <p className="eyebrow">
            <span aria-hidden="true" className="live-dot" />
            Данные обновляются каждую минуту
          </p>
          <h1>
            Что прямо сейчас
            <strong> происходит с интернетом</strong>
          </h1>
          <p>
            Levik Monitor сравнивает DNS, TCP, TLS, сайты, API и CDN с независимых точек,
            чтобы отличить общий сбой от проблемы провайдера или маршрута.
          </p>
          <div className="button-row">
            <a className="button button--primary button--large" href="#my-connection">
              <RouteIcon />
              Почему не работает у меня?
            </a>
            <Link className="button button--ghost button--large" href="https://mon.leviknet.com/methodology">
              <GlobeIcon />
              Как работает диагностика
            </Link>
          </div>
        </div>
        <article className={`internet-index internet-index--${overview.state}`}>
          <div className="internet-index__orb">
            <PulseIcon />
            <strong>{overview.index ?? "—"}</strong>
            <span>/ 100</span>
          </div>
          <span>Levik Internet Index</span>
          <h2>{pulseDescription(overview)}</h2>
          <dl>
            <div><dt>Точек онлайн</dt><dd>{overview.activeProbeCount}</dd></div>
            <div><dt>Сервисов</dt><dd>{overview.services.length}</dd></div>
          </dl>
          <small>{updatedLabel(overview.updatedAt)}</small>
        </article>
      </section>

      <section className="landing-section monitor-services-section">
        <div className="container">
          <div className="section-heading">
            <span>Интернет-пульс</span>
            <h2>Популярные сервисы сейчас</h2>
            <p>Статус — это вывод по нескольким уровням проверки, а не один ping.</p>
          </div>
          <div className="monitor-services-grid">
            {overview.services.map((snapshot) => (
              <Link className={`monitor-service-card monitor-service-card--${snapshot.state}`} href={`https://mon.leviknet.com/${snapshot.service.slug}`} key={snapshot.service.slug}>
                <div className="monitor-service-card__head">
                  <ServiceIcon
                    className="monitor-service-card__mark"
                    name={snapshot.service.name}
                    slug={snapshot.service.slug}
                  />
                  <div>
                    <h3>{snapshot.service.name}</h3>
                    <p>{snapshot.service.summary}</p>
                  </div>
                  <ArrowUpRightIcon />
                </div>
                <MonitorStateBadge state={snapshot.state} />
                <div className="monitor-availability">
                  <span><i style={{ width: `${snapshot.availability ?? 0}%` }} /></span>
                  <strong>{snapshot.availability === null ? "Доступность: нет данных" : `Доступность: ${snapshot.availability}%`}</strong>
                </div>
                <p className={`monitor-quality monitor-quality--${snapshot.quality}`}>Качество соединения: {qualityLabel(snapshot.quality)}</p>
                <p className="monitor-service-card__diagnosis">{snapshot.diagnosis}</p>
                <dl>
                  <div><dt>Точек</dt><dd>{snapshot.measurements.length}</dd></div>
                  <div><dt>Уверенность</dt><dd>{snapshot.confidence === null ? "—" : `${snapshot.confidence}%`}</dd></div>
                </dl>
              </Link>
            ))}
          </div>
        </div>
      </section>

      <section className="landing-section monitor-diagnostics-section">
        <div className="container">
          <BrowserDiagnostics services={overview.services.map((snapshot) => snapshot.service)} />
        </div>
      </section>

      <section className="landing-section monitor-explainer">
        <div className="container">
          <div className="section-heading">
            <span>Не просто красная лампочка</span>
            <h2>Monitor пытается найти место сбоя</h2>
          </div>
          <div className="monitor-explainer__grid">
            <article>
              <span>01</span><h3>Проверяет уровни</h3>
              <p>DNS, TCP 443, TLS, HTTP, API и CDN проверяются отдельно.</p>
            </article>
            <article>
              <span>02</span><h3>Сравнивает точки</h3>
              <p>Серверные точки Levik и проверки пользователей показываются отдельно, чтобы не смешивать разные источники данных.</p>
            </article>
            <article>
              <span>03</span><h3>Оценивает уверенность</h3>
              <p>При нехватке свежих данных Monitor честно не делает категоричный вывод.</p>
            </article>
          </div>
        </div>
      </section>

      <section className="monitor-main-cta">
        <div className="container monitor-main-cta__inner">
          <div>
            <span className="section-kicker">Levik VPN</span>
            <h2>Проблема в маршруте? Проверьте другой путь</h2>
            <p>Monitor остаётся бесплатным для всех. Levik VPN нужен только когда вы хотите сменить маршрут подключения.</p>
          </div>
          <Link className="button button--primary button--large" href="https://leviknet.com/">
            Перейти на основной сайт
            <ArrowUpRightIcon />
          </Link>
        </div>
      </section>
    </>
  );
}
