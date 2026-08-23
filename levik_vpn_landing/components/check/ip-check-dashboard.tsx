"use client";

import Link from "next/link";
import type { CSSProperties } from "react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import {
  CheckIcon,
  ClockIcon,
  ConnectIcon,
  CopyIcon,
  DnsIcon,
  GlobeIcon,
  NetworkScanIcon,
  RefreshIcon,
  ShieldCheckIcon,
  SignalIcon,
} from "@/components/icons";
import { isIpCheckSnapshot, type IpCheckSnapshot } from "@/lib/ip-check";

type CheckPhase = "loading" | "ready" | "error";
type OptionalProbePhase = "checking" | "ready";
type ProbeState = "checking" | "online" | "offline";
type WebRtcResult = {
  state: "checking" | "safe" | "leak" | "hidden" | "unsupported";
  address: string | null;
};
type BrowserProfile = {
  browser: string;
  platform: string;
  language: string;
  timezone: string;
  cookies: boolean;
  doNotTrack: boolean;
};
type ServiceProbe = {
  id: "google" | "youtube" | "telegram" | "vk";
  label: string;
  url: string;
  state: ProbeState;
};
type RadarPoint = {
  id: string;
  delay: number;
  left: number;
  top: number;
};
type RadarPointStyle = CSSProperties & {
  "--radar-node-delay": string;
};

const RADAR_DURATION_SECONDS = 4;
const INITIAL_RADAR_POINTS: RadarPoint[] = [
  { id: "initial-1", delay: 0.5, left: 34, top: 28 },
  { id: "initial-2", delay: 1.7, left: 73, top: 56 },
  { id: "initial-3", delay: 2.8, left: 41, top: 78 },
];

const SERVICE_TARGETS: Omit<ServiceProbe, "state">[] = [
  {
    id: "google",
    label: "Google",
    url: "https://www.google.com/generate_204",
  },
  {
    id: "youtube",
    label: "YouTube",
    url: "https://www.youtube.com/generate_204",
  },
  {
    id: "telegram",
    label: "Telegram",
    url: "https://telegram.org/img/t_logo.png",
  },
  {
    id: "vk",
    label: "VK",
    url: "https://vk.com/robots.txt",
  },
];

function initialServices(): ServiceProbe[] {
  return SERVICE_TARGETS.map((service) => ({ ...service, state: "checking" }));
}

function createRadarPoints(): RadarPoint[] {
  const scanId = `${Date.now()}-${Math.random()}`;

  return Array.from({ length: 3 }, (_, index) => {
    const angle = Math.random() * Math.PI * 2;
    const radius = 22 + Math.random() * 21;

    return {
      id: `${scanId}-${index}`,
      delay: (angle / (Math.PI * 2)) * RADAR_DURATION_SECONDS,
      left: 50 + Math.sin(angle) * radius,
      top: 50 - Math.cos(angle) * radius,
    };
  });
}

function radarPointStyle(point: RadarPoint): RadarPointStyle {
  return {
    "--radar-node-delay": `${point.delay}s`,
    left: `${point.left}%`,
    top: `${point.top}%`,
  };
}

function ServiceLogo({ service }: { service: ServiceProbe["id"] }) {
  switch (service) {
    case "google":
      return (
        <svg aria-hidden="true" viewBox="0 0 24 24">
          <path d="M21.6 12.23c0-.71-.06-1.4-.19-2.07H12v3.92h5.38a4.6 4.6 0 0 1-2 3.02v2.54h3.24c1.9-1.75 2.98-4.33 2.98-7.41Z" fill="#4285F4" />
          <path d="M12 22c2.7 0 4.97-.9 6.62-2.36l-3.24-2.54c-.9.6-2.05.96-3.38.96-2.61 0-4.82-1.76-5.61-4.13H3.04v2.62A10 10 0 0 0 12 22Z" fill="#34A853" />
          <path d="M6.39 13.93A6.01 6.01 0 0 1 6.07 12c0-.67.12-1.32.32-1.93V7.45H3.04A10 10 0 0 0 2 12c0 1.61.38 3.13 1.04 4.55l3.35-2.62Z" fill="#FBBC05" />
          <path d="M12 5.94c1.47 0 2.79.5 3.83 1.5l2.87-2.87A9.62 9.62 0 0 0 12 2a10 10 0 0 0-8.96 5.45l3.35 2.62C7.18 7.7 9.39 5.94 12 5.94Z" fill="#EA4335" />
        </svg>
      );
    case "youtube":
      return (
        <svg aria-hidden="true" viewBox="0 0 24 24">
          <path d="M23.5 6.2a3 3 0 0 0-2.1-2.12C19.55 3.58 12 3.58 12 3.58s-7.55 0-9.4.5A3 3 0 0 0 .5 6.2 31.2 31.2 0 0 0 0 12a31.2 31.2 0 0 0 .5 5.8 3 3 0 0 0 2.1 2.12c1.85.5 9.4.5 9.4.5s7.55 0 9.4-.5a3 3 0 0 0 2.1-2.12A31.2 31.2 0 0 0 24 12a31.2 31.2 0 0 0-.5-5.8Z" fill="#FF0033" />
          <path d="m9.55 15.57 6.27-3.57-6.27-3.57v7.14Z" fill="#fff" />
        </svg>
      );
    case "telegram":
      return (
        <svg aria-hidden="true" viewBox="0 0 24 24">
          <circle cx="12" cy="12" fill="#27A7E7" r="12" />
          <path d="M17.62 6.62 5.98 11.1c-.8.32-.8.77-.15.97l2.99.93 1.15 3.57c.14.39.07.54.48.54.32 0 .46-.14.63-.3l1.45-1.41 3.01 2.22c.56.31.96.15 1.1-.52l1.99-9.38c.2-.81-.31-1.18-1.01-1.1Zm-8.33 6.17 6.73-4.25c.34-.21.65-.1.4.12l-5.56 5.02-.22 2.31-1.35-3.2Z" fill="#fff" />
        </svg>
      );
    case "vk":
      return (
        <svg aria-hidden="true" viewBox="0 0 24 24">
          <rect fill="#0077FF" height="24" rx="6" width="24" />
          <path d="M12.73 17.2C7.27 17.2 4.15 13.46 4 7.24h2.74c.09 4.57 2.1 6.5 3.7 6.9v-6.9h2.58v3.94c1.58-.17 3.24-1.96 3.8-3.94h2.58a7.6 7.6 0 0 1-3.5 4.96 7.88 7.88 0 0 1 4.1 5h-2.84c-.62-1.9-2.14-3.38-4.14-3.58v3.58h-.3Z" fill="#fff" />
        </svg>
      );
  }
}

function browserName(userAgent: string): string {
  const candidates: [RegExp, string][] = [
    [/Edg\/(\d+)/, "Microsoft Edge"],
    [/OPR\/(\d+)/, "Opera"],
    [/YaBrowser\/(\d+)/, "Яндекс Браузер"],
    [/Firefox\/(\d+)/, "Firefox"],
    [/Chrome\/(\d+)/, "Chrome"],
    [/Version\/(\d+).+Safari/, "Safari"],
  ];
  const found = candidates.find(([pattern]) => pattern.test(userAgent));
  if (!found) return "Не определён";
  const version = userAgent.match(found[0])?.[1];
  return version ? `${found[1]} ${version}` : found[1];
}

function platformName(userAgent: string, platform: string): string {
  if (/Android/i.test(userAgent)) return "Android";
  if (/iPhone|iPad|iPod/i.test(userAgent)) return "iOS / iPadOS";
  if (/Windows/i.test(userAgent)) return "Windows";
  if (/Mac/i.test(platform) || /Mac OS/i.test(userAgent)) return "macOS";
  if (/Linux/i.test(platform) || /Linux/i.test(userAgent)) return "Linux";
  return platform || "Не определена";
}

function readBrowserProfile(): BrowserProfile {
  return {
    browser: browserName(navigator.userAgent),
    platform: platformName(navigator.userAgent, navigator.platform),
    language: navigator.language || "Не определён",
    timezone:
      Intl.DateTimeFormat().resolvedOptions().timeZone || "Не определён",
    cookies: navigator.cookieEnabled,
    doNotTrack: navigator.doNotTrack === "1",
  };
}

async function measureLatency(): Promise<number | null> {
  const samples: number[] = [];
  for (let index = 0; index < 4; index += 1) {
    const startedAt = performance.now();
    try {
      const response = await fetch(`/api/check/ping?sample=${index}-${Date.now()}`, {
        cache: "no-store",
        signal: AbortSignal.timeout(4_000),
      });
      if (!response.ok) return null;
      samples.push(performance.now() - startedAt);
    } catch {
      return null;
    }
  }
  const measured = samples.slice(1);
  return Math.round(measured.reduce((sum, value) => sum + value, 0) / measured.length);
}

async function detectIpv6(): Promise<string | null> {
  try {
    const response = await fetch(`https://api6.ipify.org?format=json&t=${Date.now()}`, {
      cache: "no-store",
      signal: AbortSignal.timeout(4_000),
    });
    if (!response.ok) return null;
    const payload: unknown = await response.json();
    if (
      typeof payload === "object" &&
      payload !== null &&
      "ip" in payload &&
      typeof payload.ip === "string" &&
      payload.ip.includes(":")
    ) {
      return payload.ip;
    }
  } catch {
    return null;
  }
  return null;
}

function isPrivateCandidate(address: string): boolean {
  return (
    address.endsWith(".local") ||
    /^10\./.test(address) ||
    /^192\.168\./.test(address) ||
    /^172\.(1[6-9]|2\d|3[01])\./.test(address) ||
    /^169\.254\./.test(address) ||
    /^(fc|fd|fe8|fe9|fea|feb)/i.test(address)
  );
}

async function detectWebRtc(httpAddress: string): Promise<WebRtcResult> {
  if (!("RTCPeerConnection" in window)) {
    return { state: "unsupported", address: null };
  }

  const peer = new RTCPeerConnection({
    iceServers: [{ urls: "stun:stun.l.google.com:19302" }],
  });
  const candidates = new Set<string>();
  peer.createDataChannel("network-check");

  try {
    await new Promise<void>((resolve, reject) => {
      const timeout = window.setTimeout(resolve, 4_500);
      peer.onicecandidate = (event) => {
        if (!event.candidate) {
          window.clearTimeout(timeout);
          resolve();
          return;
        }
        const candidate = event.candidate.candidate;
        const address = event.candidate.address || candidate.split(" ")[4];
        if (address && !isPrivateCandidate(address)) candidates.add(address);
      };

      void (async () => {
        try {
          const offer = await peer.createOffer();
          await peer.setLocalDescription(offer);
        } catch (error) {
          window.clearTimeout(timeout);
          reject(
            error instanceof Error
              ? error
              : new Error("webrtc_candidate_collection_failed"),
          );
        }
      })();
    });
  } catch {
    peer.close();
    return { state: "hidden", address: null };
  }
  peer.close();

  const exposed = [...candidates];
  const different = exposed.find(
    (candidate) => candidate.toLowerCase() !== httpAddress.toLowerCase(),
  );
  if (different) return { state: "leak", address: different };
  if (exposed.length > 0) return { state: "safe", address: exposed[0] };
  return { state: "hidden", address: null };
}

async function probeService(service: Omit<ServiceProbe, "state">): Promise<ServiceProbe> {
  try {
    await fetch(`${service.url}${service.url.includes("?") ? "&" : "?"}t=${Date.now()}`, {
      cache: "no-store",
      mode: "no-cors",
      signal: AbortSignal.timeout(5_000),
    });
    return { ...service, state: "online" };
  } catch {
    return { ...service, state: "offline" };
  }
}

function valueOrDash(value: string | number | null): string {
  return value === null || value === "" ? "Нет данных" : String(value);
}

function dateOrDash(value: string | null | undefined): string {
  if (!value) return "Нет данных";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Нет данных";
  return new Intl.DateTimeFormat("ru-RU", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
  }).format(date);
}

function webRtcLabel(result: WebRtcResult): string {
  switch (result.state) {
    case "safe":
      return "Утечка не обнаружена";
    case "leak":
      return "Обнаружен другой публичный IP";
    case "hidden":
      return "Публичный IP скрыт";
    case "unsupported":
      return "Не поддерживается";
    default:
      return "Проверяется";
  }
}

export function IpCheckDashboard() {
  const [phase, setPhase] = useState<CheckPhase>("loading");
  const [snapshot, setSnapshot] = useState<IpCheckSnapshot | null>(null);
  const [latency, setLatency] = useState<number | null>(null);
  const [latencyPhase, setLatencyPhase] =
    useState<OptionalProbePhase>("checking");
  const [ipv6, setIpv6] = useState<string | null>(null);
  const [ipv6Phase, setIpv6Phase] =
    useState<OptionalProbePhase>("checking");
  const [detailsPhase, setDetailsPhase] =
    useState<OptionalProbePhase>("checking");
  const [webrtc, setWebrtc] = useState<WebRtcResult>({
    state: "checking",
    address: null,
  });
  const [services, setServices] = useState<ServiceProbe[]>(initialServices);
  const [radarPoints, setRadarPoints] =
    useState<RadarPoint[]>(INITIAL_RADAR_POINTS);
  const [profile, setProfile] = useState<BrowserProfile | null>(null);
  const [copied, setCopied] = useState(false);
  const checkGeneration = useRef(0);
  const copyFeedbackTimeout = useRef<number | null>(null);

  const runCheck = useCallback(async () => {
    const generation = checkGeneration.current + 1;
    checkGeneration.current = generation;
    const isCurrent = () => checkGeneration.current === generation;

    setPhase("loading");
    setLatency(null);
    setLatencyPhase("checking");
    setIpv6(null);
    setIpv6Phase("checking");
    setDetailsPhase("checking");
    setWebrtc({ state: "checking", address: null });
    setServices(initialServices());
    setRadarPoints(createRadarPoints());
    setProfile(readBrowserProfile());

    const detailsRequest = fetch(`/api/check/details?t=${Date.now()}`, {
      cache: "no-store",
      signal: AbortSignal.timeout(10_000),
    }).catch(() => null);

    void measureLatency().then((measuredLatency) => {
      if (!isCurrent()) return;
      setLatency(measuredLatency);
      setLatencyPhase("ready");
    });
    void detectIpv6().then((ipv6Address) => {
      if (!isCurrent()) return;
      setIpv6(ipv6Address);
      setIpv6Phase("ready");
    });
    for (const service of SERVICE_TARGETS) {
      void probeService(service).then((result) => {
        if (!isCurrent()) return;
        setServices((current) =>
          current.map((item) => (item.id === result.id ? result : item)),
        );
      });
    }

    try {
      const response = await fetch(`/api/check?t=${Date.now()}`, {
        cache: "no-store",
        signal: AbortSignal.timeout(8_000),
      });
      if (!response.ok) throw new Error("ip_check_unavailable");
      const payload: unknown = await response.json();
      if (!isIpCheckSnapshot(payload)) throw new Error("invalid_ip_check_payload");
      if (!isCurrent()) return;

      setSnapshot(payload);
      setPhase("ready");
      void detailsRequest
        .then(async (detailsResponse) => {
          if (!detailsResponse?.ok) return null;
          const detailsPayload: unknown = await detailsResponse.json();
          return isIpCheckSnapshot(detailsPayload) ? detailsPayload : null;
        })
        .then((detailsPayload) => {
          if (!isCurrent() || !detailsPayload) return;
          setSnapshot((current) =>
            current?.ip === detailsPayload.ip ? detailsPayload : current,
          );
        })
        .catch(() => undefined)
        .finally(() => {
          if (isCurrent()) setDetailsPhase("ready");
        });
      void detectWebRtc(payload.ip).then((result) => {
        if (isCurrent()) setWebrtc(result);
      });
    } catch {
      if (isCurrent()) setPhase("error");
    }
  }, []);

  useEffect(() => {
    void runCheck();
    return () => {
      checkGeneration.current += 1;
      if (copyFeedbackTimeout.current !== null) {
        window.clearTimeout(copyFeedbackTimeout.current);
      }
    };
  }, [runCheck]);

  const location = useMemo(() => {
    if (!snapshot) return "Определяем местоположение";
    return [snapshot.city, snapshot.region, snapshot.country]
      .filter((value, index, values) => value && values.indexOf(value) === index)
      .join(", ") || "Местоположение не определено";
  }, [snapshot]);

  const copyIp = useCallback(async () => {
    if (!snapshot) return;
    await navigator.clipboard.writeText(snapshot.ip);
    setCopied(true);
    if (copyFeedbackTimeout.current !== null) {
      window.clearTimeout(copyFeedbackTimeout.current);
    }
    copyFeedbackTimeout.current = window.setTimeout(() => {
      setCopied(false);
      copyFeedbackTimeout.current = null;
    }, 1_800);
  }, [snapshot]);

  const protection = snapshot?.protection ?? "unknown";
  const verdictTitle =
    detailsPhase === "checking"
      ? "Проверяем защиту Levik"
      : protection === "protected"
      ? "Вы защищены Levik"
      : protection === "direct"
        ? "Соединение напрямую"
        : "Защита не подтверждена";
  const verdictText =
    detailsPhase === "checking"
      ? "IP уже определён. Сверяем маршрут с активными выходными узлами Levik."
      : protection === "protected"
      ? "Текущий IP совпадает с активным маршрутом сети Levik."
      : protection === "direct"
        ? "Ваше текущее подключение не защищено Levik VPN"
        : "Не удалось сверить IP с сетью Levik. Повторите проверку через несколько секунд.";

  return (
    <>
      <section className="container check-hero" aria-labelledby="check-title">
        <div className="check-hero__copy">
          <p className="eyebrow">
            <span aria-hidden="true" className="live-dot" />
            Диагностика соединения в реальном времени
          </p>
          <h1 id="check-title">
            Узнать мой IP
            <strong>и проверить защиту</strong>
          </h1>
        </div>
        <div className="check-hero__radar" aria-hidden="true">
          <div className="check-radar__ring check-radar__ring--one" />
          <div className="check-radar__ring check-radar__ring--two" />
          <div
            className="check-radar__sweep"
            key={radarPoints[0]?.id ?? "initial-sweep"}
          />
          {radarPoints.map((point) => (
            <span
              className="check-radar__node"
              key={point.id}
              style={radarPointStyle(point)}
            />
          ))}
          <NetworkScanIcon />
        </div>
      </section>

      <section className="container check-console" aria-live="polite">
        <div className="check-console__topline">
          <span>LEVIK NETWORK DIAGNOSTICS</span>
          <span className={`check-console__phase check-console__phase--${phase}`}>
            {phase === "loading"
              ? "СКАНИРОВАНИЕ"
              : phase === "ready"
                ? "СИСТЕМА ГОТОВА"
                : "ОШИБКА СВЯЗИ"}
          </span>
          <button
            className="button button--ghost check-refresh"
            disabled={phase === "loading"}
            onClick={() => void runCheck()}
            type="button"
          >
            <RefreshIcon />
            Проверить снова
          </button>
        </div>

        {phase === "error" ? (
          <div className="check-error" role="alert">
            <ShieldCheckIcon />
            <div>
              <strong>Не удалось завершить диагностику</strong>
              <p>Проверьте соединение и запустите сканирование ещё раз.</p>
            </div>
          </div>
        ) : (
          <>
            <div className="check-ip-panel">
              <div className="check-ip-panel__label">
                <span className="check-card__icon"><GlobeIcon /></span>
                <span>Ваш публичный IP</span>
              </div>
              <div className="check-ip-panel__address">
                <strong
                  className={`check-ip-panel__address-value${snapshot?.version === "IPv6" ? " check-ip-panel__address-value--ipv6" : ""}`}
                >
                  {snapshot?.ip ?? "•••.•••.•••.•••"}
                </strong>
                <span className="check-copy__wrapper">
                  <button
                    aria-label={copied ? "IP-адрес скопирован" : "Скопировать IP-адрес"}
                    className={`check-copy${copied ? " check-copy--copied" : ""}`}
                    disabled={!snapshot}
                    onClick={() => void copyIp()}
                    title={copied ? "Скопировано" : "Скопировать"}
                    type="button"
                  >
                    {copied ? <CheckIcon /> : <CopyIcon />}
                  </button>
                  {copied ? (
                    <span className="check-copy__feedback" role="status">
                      Скопировано
                    </span>
                  ) : null}
                </span>
              </div>
              <div className="check-ip-panel__location">
                <span>{snapshot?.flagEmoji ?? "◎"}</span>
                {location}
              </div>
              <div className="check-ip-panel__chips">
                <span>{snapshot?.version ?? "IP"}</span>
                <span>
                  {ipv6Phase === "checking"
                    ? "IPv6 проверяется"
                    : ipv6
                      ? "IPv6 доступен"
                      : "IPv6 не обнаружен"}
                </span>
                <span>
                  {latencyPhase === "checking"
                    ? "Пинг проверяется"
                    : latency === null
                      ? "Пинг недоступен"
                      : `Пинг ${latency} мс`}
                </span>
              </div>
            </div>

            <Link
              className={`check-verdict check-verdict--${protection}`}
              href={protection === "protected" ? "https://leviknet.com/dashboard" : "https://leviknet.com/"}
            >
              <span className="check-verdict__icon"><ShieldCheckIcon /></span>
              <span>
                <strong>{verdictTitle}</strong>
                <small>{verdictText}</small>
              </span>
              <ConnectIcon />
            </Link>

            <div className="check-grid">
              <article className="check-card">
                <div className="check-card__heading">
                  <span className="check-card__icon"><SignalIcon /></span>
                  <div><span>Сеть</span><h2>Маршрут соединения</h2></div>
                </div>
                <dl className="check-data-list">
                  <div><dt>Провайдер</dt><dd>{valueOrDash(snapshot?.provider ?? null)}</dd></div>
                  <div><dt>Организация</dt><dd>{valueOrDash(snapshot?.organization ?? null)}</dd></div>
                  <div><dt>AS Number</dt><dd>{snapshot?.asn ? `AS${snapshot.asn}` : "Нет данных"}</dd></div>
                  <div><dt>Задержка</dt><dd>{latency === null ? "Нет данных" : `${latency} мс`}</dd></div>
                </dl>
              </article>

              <article className="check-card">
                <div className="check-card__heading">
                  <span className="check-card__icon"><GlobeIcon /></span>
                  <div><span>Геолокация IP</span><h2>Точка выхода</h2></div>
                </div>
                <dl className="check-data-list">
                  <div><dt>Страна</dt><dd>{valueOrDash(snapshot?.country ?? null)}</dd></div>
                  <div><dt>Регион</dt><dd>{valueOrDash(snapshot?.region ?? null)}</dd></div>
                  <div><dt>Город</dt><dd>{valueOrDash(snapshot?.city ?? null)}</dd></div>
                  <div><dt>Часовой пояс</dt><dd>{valueOrDash(snapshot?.timezone ?? null)}</dd></div>
                </dl>
              </article>

              <article className="check-card">
                <div className="check-card__heading">
                  <span className="check-card__icon"><DnsIcon /></span>
                  <div><span>DNS</span><h2>Контур резолвинга</h2></div>
                </div>
                <div className={`check-signal check-signal--${protection}`}>
                  <span />
                  <strong>
                    {protection === "protected"
                      ? "Маршрут через Levik"
                      : protection === "direct"
                        ? "Системный маршрут"
                        : "Маршрут не подтверждён"}
                  </strong>
                </div>
                <p className="check-card__note">
                  Браузеры скрывают адрес системного DNS. Поэтому мы показываем
                  подтверждённый сетевой маршрут и не выдаём приблизительный
                  результат за полноценный DNS leak-тест.
                </p>
              </article>

              <article className="check-card">
                <div className="check-card__heading">
                  <span className="check-card__icon"><NetworkScanIcon /></span>
                  <div><span>WebRTC leak</span><h2>Публичные кандидаты</h2></div>
                </div>
                <div className={`check-signal check-signal--${webrtc.state === "leak" ? "danger" : "safe"}`}>
                  <span />
                  <strong>{webRtcLabel(webrtc)}</strong>
                </div>
                <p className="check-card__note">
                  {webrtc.address
                    ? `WebRTC сообщает адрес ${webrtc.address}.`
                    : "Локальные адреса, скрытые через mDNS, не считаются утечкой."}
                </p>
              </article>

              <article className="check-card check-card--wide">
                <div className="check-card__heading">
                  <span className="check-card__icon"><NetworkScanIcon /></span>
                  <div><span>RDAP / WHOIS</span><h2>Регистрация IP-диапазона</h2></div>
                </div>
                <dl className="check-data-list check-data-list--columns">
                  <div><dt>Владелец</dt><dd>{valueOrDash(snapshot?.registration?.name ?? null)}</dd></div>
                  <div><dt>Реестр</dt><dd>{valueOrDash(snapshot?.registration?.registry ?? null)}</dd></div>
                  <div><dt>Сеть</dt><dd>{valueOrDash(snapshot?.registration?.network ?? null)}</dd></div>
                  <div><dt>Диапазон</dt><dd>{snapshot?.registration ? `${snapshot.registration.rangeStart} — ${snapshot.registration.rangeEnd}` : "Нет данных"}</dd></div>
                  <div><dt>Handle</dt><dd>{valueOrDash(snapshot?.registration?.handle ?? null)}</dd></div>
                  <div><dt>Тип</dt><dd>{valueOrDash(snapshot?.registration?.type ?? null)}</dd></div>
                  <div><dt>Регистрация</dt><dd>{dateOrDash(snapshot?.registration?.registeredAt)}</dd></div>
                  <div><dt>Обновление</dt><dd>{dateOrDash(snapshot?.registration?.updatedAt)}</dd></div>
                </dl>
              </article>
            </div>

            <section className="check-services" aria-labelledby="services-title">
              <div className="check-section-heading">
                <div>
                  <span>Прямой тест из браузера</span>
                  <h2 id="services-title">Доступность популярных сервисов</h2>
                </div>
                <p>Проверяется именно ваш маршрут, а не соединение VPS.</p>
              </div>
              <div className="check-services__grid">
                {services.map((service) => (
                  <article className="service-probe" key={service.id}>
                    <span className={`service-probe__logo service-probe__logo--${service.id}`}>
                      <ServiceLogo service={service.id} />
                    </span>
                    <div><strong>{service.label}</strong><span>{service.state === "checking" ? "Проверяется" : service.state === "online" ? "Доступен" : "Не отвечает"}</span></div>
                    <span className={`service-probe__state service-probe__state--${service.state}`} />
                  </article>
                ))}
              </div>
            </section>

            <section className="check-profile" aria-labelledby="profile-title">
              <div className="check-section-heading">
                <div>
                  <span>Цифровой профиль</span>
                  <h2 id="profile-title">Что сообщает браузер</h2>
                </div>
                <p>Без создания постоянного fingerprint-идентификатора.</p>
              </div>
              <dl className="check-profile__grid">
                <div><dt>Браузер</dt><dd>{profile?.browser ?? "Определяется"}</dd></div>
                <div><dt>Система</dt><dd>{profile?.platform ?? "Определяется"}</dd></div>
                <div><dt>Язык</dt><dd>{profile?.language ?? "Определяется"}</dd></div>
                <div><dt>Часовой пояс</dt><dd>{profile?.timezone ?? "Определяется"}</dd></div>
                <div><dt>Cookies</dt><dd>{profile ? (profile.cookies ? "Включены" : "Отключены") : "—"}</dd></div>
                <div><dt>Do Not Track</dt><dd>{profile ? (profile.doNotTrack ? "Включён" : "Не включён") : "—"}</dd></div>
              </dl>
            </section>
          </>
        )}
      </section>

      <section className="container check-explainer" aria-labelledby="about-check-title">
        <div>
          <span className="section-kicker">Проверка без мифов</span>
          <h2 id="about-check-title">Что можно узнать по IP-адресу</h2>
        </div>
        <div className="check-explainer__copy">
          <p>
            Публичный IP показывает, через какую сеть устройство выходит в
            интернет. По нему можно приблизительно определить страну, город,
            провайдера и автономную систему. Это не домашний адрес и не точная
            геолокация устройства.
          </p>
          <p>
            При включённом Levik VPN внешний ресурс должен видеть адрес
            выходного узла Levik, а не адрес домашнего или мобильного
            провайдера. Нажмите «Проверить снова» после подключения.
          </p>
        </div>
      </section>

      <section className="container check-faq" aria-labelledby="faq-title">
        <div className="check-section-heading">
          <div><span>Коротко о главном</span><h2 id="faq-title">Вопросы об IP и VPN</h2></div>
        </div>
        <div className="check-faq__grid">
          <details><summary>Как узнать свой IP-адрес?</summary><p>Текущий публичный IP появляется в верхней части страницы автоматически. Устанавливать приложение или разрешать доступ к геолокации не нужно.</p></details>
          <details><summary>Почему местоположение приблизительное?</summary><p>IP привязан к сети провайдера, а не к GPS устройства. Город может совпадать с точкой присутствия оператора или VPN-сервера.</p></details>
          <details><summary>Как понять, что Levik VPN работает?</summary><p>Статус «Вы защищены Levik» появляется только после совпадения текущего IP с живым списком выходных узлов Levik.</p></details>
          <details><summary>Что означает WebRTC leak?</summary><p>WebRTC иногда раскрывает дополнительный публичный адрес в обход ожидаемого маршрута. Если он отличается от основного IP, страница покажет предупреждение.</p></details>
          <details><summary>Что показывает WHOIS IP-адреса?</summary><p>Блок RDAP / WHOIS показывает официальную регистрацию сети: выделенный диапазон, региональный реестр, владельца блока и даты обновления. Эти сведения относятся к сети провайдера, а не к конкретному пользователю.</p></details>
        </div>
      </section>

      <section className="container check-privacy-note">
        <ClockIcon />
        <p>
          Результат обновляется только по вашему запросу. Геолокация и ASN
          определяются в локальной базе Levik без передачи IP стороннему
          GeoIP API; регистрационные данные кэшируются из официальных RDAP
          реестров. Проверка IPv6 обращается к IPify. Данные геолокации:{" "}
          <a href="https://db-ip.com" rel="noreferrer" target="_blank">
            DB-IP
          </a>. Levik не создаёт постоянный цифровой отпечаток браузера.
        </p>
      </section>
    </>
  );
}
