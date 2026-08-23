"use client";

import { geoGraticule10, geoOrthographic, geoPath } from "d3-geo";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";

import {
  GlobeIcon,
  PauseIcon,
  PlayIcon,
  RefreshIcon,
} from "@/components/icons";
import type {
  PublicServerStatus,
  ServerState,
  StatusSnapshot,
} from "@/lib/status";

type Position = [longitude: number, latitude: number];

type GeoFeature = {
  type: "Feature";
  properties: {
    ISO_A2: string;
    ISO_A2_EH?: string;
    LABEL_X: number;
    LABEL_Y: number;
    NAME_RU: string;
  };
  geometry:
    | { type: "Polygon"; coordinates: Position[][] }
    | { type: "MultiPolygon"; coordinates: Position[][][] };
};

type GeoFeatureCollection = {
  type: "FeatureCollection";
  features: GeoFeature[];
};

type GlobeProps = {
  servers: PublicServerStatus[];
};

type ConnectionState = "loading" | "ok" | "stale" | "error";

const RUSSIA_CENTER: Position = [94.25, 66.42];
const INITIAL_GLOBE_VIEW: { yaw: number; pitch: number; scale: number } = {
  yaw: -1.05,
  pitch: 0.38,
  scale: 1,
};
const STATE_PRIORITY: Record<ServerState, number> = {
  online: 0,
  maintenance: 1,
  degraded: 2,
  offline: 3,
};
const STATE_LABELS: Record<ServerState, string> = {
  online: "Доступен",
  degraded: "Подключается",
  maintenance: "Обслуживание",
  offline: "Недоступен",
};
const STATE_COLORS: Record<ServerState, string> = {
  online: "#31e8ff",
  degraded: "#ffcc6a",
  maintenance: "#a28fff",
  offline: "#ff6e99",
};
const STATE_FILL_COLORS: Record<ServerState, string> = {
  online: "#155a78",
  degraded: "#574e3e",
  maintenance: "#413f6b",
  offline: "#57334f",
};
const DEFAULT_COUNTRY_FILL = "#173d5d";
const MIN_GLOBE_SCALE = 0.72;
const MAX_GLOBE_SCALE = 1.8;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isGeoFeatureCollection(value: unknown): value is GeoFeatureCollection {
  return (
    isRecord(value) &&
    value.type === "FeatureCollection" &&
    Array.isArray(value.features)
  );
}

function isStatusSnapshot(value: unknown): value is StatusSnapshot {
  return (
    isRecord(value) &&
    Array.isArray(value.servers) &&
    typeof value.fetchedAt === "string" &&
    (value.source === "live" || value.source === "stale") &&
    typeof value.controlLatencyMs === "number"
  );
}

function CountryFlag({ code }: { code: string }) {
  const normalizedCode = code.toLowerCase();
  if (!/^[a-z]{2}$/.test(normalizedCode)) return null;
  return <span aria-hidden="true" className={`country-flag fi fi-${normalizedCode}`} />;
}

const countryNames = new Intl.DisplayNames(["ru"], { type: "region" });

function countryName(code: string): string {
  return countryNames.of(code) ?? code;
}

function featureCountryCode(feature: GeoFeature): string | null {
  const { ISO_A2, ISO_A2_EH } = feature.properties;
  if (/^[A-Z]{2}$/.test(ISO_A2)) return ISO_A2;
  if (ISO_A2_EH && /^[A-Z]{2}$/.test(ISO_A2_EH)) return ISO_A2_EH;
  return null;
}

function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 Б";
  const units = ["Б", "КБ", "МБ", "ГБ", "ТБ", "ПБ"];
  const unit = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  const value = bytes / 1024 ** unit;
  return `${new Intl.NumberFormat("ru-RU", {
    maximumFractionDigits: value >= 100 ? 0 : 1,
  }).format(value)} ${units[unit]}`;
}

function formatUptime(seconds: number | null): string {
  if (seconds === null) return "Нет данных";
  const days = Math.floor(seconds / 86_400);
  if (days > 0) return `${days} дн.`;
  const hours = Math.floor(seconds / 3_600);
  if (hours > 0) return `${hours} ч.`;
  return `${Math.max(1, Math.floor(seconds / 60))} мин.`;
}

function formatUpdatedAt(value: string): string {
  return new Intl.DateTimeFormat("ru-RU", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    timeZone: "Europe/Moscow",
  }).format(new Date(value));
}

function sphericalPoint([longitude, latitude]: Position) {
  const lon = (longitude * Math.PI) / 180;
  const lat = (latitude * Math.PI) / 180;
  const cosLat = Math.cos(lat);
  return {
    x: cosLat * Math.sin(lon),
    y: Math.sin(lat),
    z: cosLat * Math.cos(lon),
  };
}

function normalizedLongitude(longitude: number, reference: number): number {
  let normalized = longitude;
  while (normalized - reference > 180) normalized -= 360;
  while (normalized - reference < -180) normalized += 360;
  return normalized;
}

function pointInRing(point: Position, ring: Position[]): boolean {
  let inside = false;
  for (let current = 0, previous = ring.length - 1; current < ring.length; previous = current++) {
    const currentLongitude = normalizedLongitude(ring[current][0], point[0]);
    const previousLongitude = normalizedLongitude(ring[previous][0], point[0]);
    const currentLatitude = ring[current][1];
    const previousLatitude = ring[previous][1];
    const intersects =
      currentLatitude > point[1] !== previousLatitude > point[1] &&
      point[0] <
        ((previousLongitude - currentLongitude) * (point[1] - currentLatitude)) /
          (previousLatitude - currentLatitude) +
          currentLongitude;
    if (intersects) inside = !inside;
  }
  return inside;
}

function featureContainsPoint(feature: GeoFeature, point: Position): boolean {
  const polygons =
    feature.geometry.type === "Polygon"
      ? [feature.geometry.coordinates]
      : feature.geometry.coordinates;
  return polygons.some(
    (polygon) =>
      polygon[0] !== undefined &&
      pointInRing(point, polygon[0]) &&
      !polygon.slice(1).some((hole) => pointInRing(point, hole)),
  );
}

function slerp(start: Position, end: Position, progress: number) {
  const from = sphericalPoint(start);
  const to = sphericalPoint(end);
  const dot = Math.max(-1, Math.min(1, from.x * to.x + from.y * to.y + from.z * to.z));
  const omega = Math.acos(dot);
  const sinOmega = Math.sin(omega);
  const fromWeight = sinOmega < 0.0001 ? 1 - progress : Math.sin((1 - progress) * omega) / sinOmega;
  const toWeight = sinOmega < 0.0001 ? progress : Math.sin(progress * omega) / sinOmega;
  const altitude = 1 + Math.sin(Math.PI * progress) * 0.16;
  return {
    x: (from.x * fromWeight + to.x * toWeight) * altitude,
    y: (from.y * fromWeight + to.y * toWeight) * altitude,
    z: (from.z * fromWeight + to.z * toWeight) * altitude,
  };
}

function NetworkGlobe({ servers }: GlobeProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const rotationRef = useRef({ ...INITIAL_GLOBE_VIEW });
  const [features, setFeatures] = useState<GeoFeature[]>([]);
  const [paused, setPaused] = useState(false);
  const [hoveredCountry, setHoveredCountry] = useState<{
    code: string;
    x: number;
    y: number;
  } | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    fetch("/assets/countries-110m.geojson", {
      cache: "force-cache",
      signal: controller.signal,
    })
      .then((response) => {
        if (!response.ok) throw new Error("world_map_unavailable");
        return response.json() as Promise<unknown>;
      })
      .then((payload) => {
        if (isGeoFeatureCollection(payload)) setFeatures(payload.features);
      })
      .catch(() => undefined);
    return () => controller.abort();
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || features.length === 0) return;
    const context = canvas.getContext("2d");
    if (!context) return;

    const globeProjection = geoOrthographic()
      .clipAngle(90)
      .precision(0.35);
    const globePath = geoPath(globeProjection, context);
    const globeGraticule = geoGraticule10();

    let frame = 0;
    let previousTime = performance.now();
    let dragging = false;
    let hovering = false;
    const activePointers = new Map<number, { x: number; y: number }>();
    let pinchDistance: number | null = null;
    let width = 0;
    let height = 0;
    let pixelRatio = 1;
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    const resize = () => {
      const bounds = canvas.getBoundingClientRect();
      width = Math.max(1, bounds.width);
      height = Math.max(1, bounds.height);
      pixelRatio = Math.min(window.devicePixelRatio || 1, 2);
      canvas.width = Math.round(width * pixelRatio);
      canvas.height = Math.round(height * pixelRatio);
      context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
    };

    const rotate = (point: { x: number; y: number; z: number }) => {
      const { yaw, pitch } = rotationRef.current;
      const cosYaw = Math.cos(yaw);
      const sinYaw = Math.sin(yaw);
      const yawX = point.x * cosYaw + point.z * sinYaw;
      const yawZ = -point.x * sinYaw + point.z * cosYaw;
      const cosPitch = Math.cos(pitch);
      const sinPitch = Math.sin(pitch);
      return {
        x: yawX,
        y: point.y * cosPitch - yawZ * sinPitch,
        z: point.y * sinPitch + yawZ * cosPitch,
      };
    };

    const project = (point: { x: number; y: number; z: number }, radius: number) => {
      const rotated = rotate(point);
      return {
        x: width / 2 + rotated.x * radius,
        y: height / 2 - rotated.y * radius,
        visible: rotated.z > -0.01,
        depth: rotated.z,
      };
    };

    const statusByCountry = new Map<string, ServerState>();
    for (const server of servers) {
      const current = statusByCountry.get(server.countryCode);
      if (!current || STATE_PRIORITY[server.state] > STATE_PRIORITY[current]) {
        statusByCountry.set(server.countryCode, server.state);
      }
    }

    const countryCenters = new Map<string, Position>();
    for (const feature of features) {
      const { LABEL_X, LABEL_Y } = feature.properties;
      const code = featureCountryCode(feature);
      if (code && Number.isFinite(LABEL_X) && Number.isFinite(LABEL_Y)) {
        countryCenters.set(code, [LABEL_X, LABEL_Y]);
      }
    }

    const activeFeatures = features.filter((feature) => {
      const code = featureCountryCode(feature);
      return code !== null && statusByCountry.has(code);
    });

    const routes = servers.flatMap((server, index) => {
      const center = countryCenters.get(server.countryCode);
      if (!center) return [];
      const offset = ((index % 5) - 2) * 0.42;
      return [{ server, start: [center[0] + offset, center[1] + offset * 0.45] as Position }];
    });

    const countryAtPoint = (clientX: number, clientY: number): string | null => {
      const bounds = canvas.getBoundingClientRect();
      const radius = Math.min(width, height) * 0.405 * rotationRef.current.scale;
      const screenX = (clientX - bounds.left - width / 2) / radius;
      const screenY = -(clientY - bounds.top - height / 2) / radius;
      const distanceSquared = screenX * screenX + screenY * screenY;
      if (distanceSquared > 1) return null;

      const rotatedZ = Math.sqrt(Math.max(0, 1 - distanceSquared));
      const { yaw, pitch } = rotationRef.current;
      const cosPitch = Math.cos(pitch);
      const sinPitch = Math.sin(pitch);
      const pitchY = screenY * cosPitch + rotatedZ * sinPitch;
      const pitchZ = -screenY * sinPitch + rotatedZ * cosPitch;
      const cosYaw = Math.cos(yaw);
      const sinYaw = Math.sin(yaw);
      const worldX = screenX * cosYaw - pitchZ * sinYaw;
      const worldZ = screenX * sinYaw + pitchZ * cosYaw;
      const point: Position = [
        (Math.atan2(worldX, worldZ) * 180) / Math.PI,
        (Math.asin(Math.max(-1, Math.min(1, pitchY))) * 180) / Math.PI,
      ];
      return (
        activeFeatures.map((feature) => ({ feature, code: featureCountryCode(feature) })).find(
          ({ feature }) => featureContainsPoint(feature, point),
        )?.code ?? null
      );
    };

    const draw = (time: number) => {
      const elapsed = Math.min(40, time - previousTime);
      previousTime = time;
      if (!paused && !dragging && !hovering && !reducedMotion) {
        rotationRef.current.yaw -= elapsed * 0.000035;
      }

      context.clearRect(0, 0, width, height);
      const radius = Math.min(width, height) * 0.405 * rotationRef.current.scale;
      const centerX = width / 2;
      const centerY = height / 2;
      const ocean = context.createRadialGradient(
        centerX - radius * 0.32,
        centerY - radius * 0.38,
        radius * 0.08,
        centerX,
        centerY,
        radius,
      );
      ocean.addColorStop(0, "#174f7f");
      ocean.addColorStop(0.48, "#082b52");
      ocean.addColorStop(1, "#020b1c");
      context.beginPath();
      context.arc(centerX, centerY, radius, 0, Math.PI * 2);
      context.fillStyle = ocean;
      context.shadowColor = "rgba(49, 232, 255, 0.34)";
      context.shadowBlur = 42;
      context.fill();
      context.shadowBlur = 0;

      context.save();
      context.beginPath();
      context.arc(centerX, centerY, radius, 0, Math.PI * 2);
      context.clip();

      const { yaw, pitch } = rotationRef.current;
      globeProjection
        .translate([centerX, centerY])
        .scale(radius)
        .rotate([
          (yaw * 180) / Math.PI,
          (-pitch * 180) / Math.PI,
          0,
        ]);

      for (const feature of features) {
        const code = featureCountryCode(feature);
        const state = code ? statusByCountry.get(code) : undefined;
        context.beginPath();
        globePath(feature);
        context.fillStyle = state ? STATE_FILL_COLORS[state] : DEFAULT_COUNTRY_FILL;
        context.strokeStyle = state
          ? `${STATE_COLORS[state]}b8`
          : "rgba(129, 208, 255, 0.24)";
        context.lineWidth = state ? 1.05 : 0.55;
        context.shadowColor = state ? STATE_COLORS[state] : "transparent";
        context.shadowBlur = state ? 5 : 0;
        context.fill("evenodd");
        context.stroke();
        context.shadowBlur = 0;
      }

      context.strokeStyle = "rgba(100, 211, 255, 0.13)";
      context.lineWidth = 0.6;
      context.beginPath();
      globePath(globeGraticule);
      context.stroke();

      for (const [routeIndex, route] of routes.entries()) {
        const color = STATE_COLORS[route.server.state];
        context.beginPath();
        let drawing = false;
        for (let step = 0; step <= 48; step += 1) {
          const projected = project(slerp(route.start, RUSSIA_CENTER, step / 48), radius);
          if (!projected.visible) {
            drawing = false;
            continue;
          }
          if (!drawing) {
            context.moveTo(projected.x, projected.y);
            drawing = true;
          } else {
            context.lineTo(projected.x, projected.y);
          }
        }
        context.strokeStyle = color;
        context.lineWidth = route.server.state === "online" ? 1.8 : 2.3;
        context.shadowColor = color;
        context.shadowBlur = 10;
        context.globalAlpha = 0.76;
        context.stroke();
        context.globalAlpha = 1;
        context.shadowBlur = 0;

        const pulseProgress = reducedMotion
          ? 0.52
          : (time / 2_200 + routeIndex * 0.137) % 1;
        const pulse = project(slerp(route.start, RUSSIA_CENTER, pulseProgress), radius);
        if (pulse.visible) {
          context.beginPath();
          context.arc(pulse.x, pulse.y, 2.8, 0, Math.PI * 2);
          context.fillStyle = "#ffffff";
          context.shadowColor = color;
          context.shadowBlur = 15;
          context.fill();
          context.shadowBlur = 0;
        }
      }

      context.restore();
      context.beginPath();
      context.arc(centerX, centerY, radius, 0, Math.PI * 2);
      context.strokeStyle = "rgba(107, 226, 255, 0.58)";
      context.lineWidth = 1.2;
      context.stroke();

      frame = requestAnimationFrame(draw);
    };

    const onPointerDown = (event: PointerEvent) => {
      dragging = true;
      setHoveredCountry(null);
      activePointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
      if (activePointers.size === 2) {
        const [first, second] = [...activePointers.values()];
        pinchDistance = Math.hypot(second.x - first.x, second.y - first.y);
      }
      canvas.setPointerCapture(event.pointerId);
      canvas.classList.add("is-dragging");
    };
    const onPointerMove = (event: PointerEvent) => {
      const previousPointer = activePointers.get(event.pointerId);
      if (previousPointer) {
        activePointers.set(event.pointerId, { x: event.clientX, y: event.clientY });

        if (activePointers.size >= 2) {
          const [first, second] = [...activePointers.values()];
          const nextDistance = Math.hypot(second.x - first.x, second.y - first.y);
          if (pinchDistance !== null && pinchDistance > 0) {
            rotationRef.current.scale = Math.max(
              MIN_GLOBE_SCALE,
              Math.min(
                MAX_GLOBE_SCALE,
                rotationRef.current.scale * (nextDistance / pinchDistance),
              ),
            );
          }
          pinchDistance = nextDistance;
          return;
        }

        rotationRef.current.yaw += (event.clientX - previousPointer.x) * 0.006;
        rotationRef.current.pitch = Math.max(
          -1.15,
          Math.min(
            1.15,
            rotationRef.current.pitch + (event.clientY - previousPointer.y) * 0.005,
          ),
        );
        return;
      }
      const code = countryAtPoint(event.clientX, event.clientY);
      if (!code) {
        hovering = false;
        setHoveredCountry(null);
        return;
      }
      hovering = true;
      const bounds = canvas.getBoundingClientRect();
      setHoveredCountry({
        code,
        x: Math.min(width - 155, Math.max(0, event.clientX - bounds.left)),
        y: Math.max(48, event.clientY - bounds.top),
      });
    };
    const onPointerUp = (event: PointerEvent) => {
      activePointers.delete(event.pointerId);
      dragging = activePointers.size > 0;
      pinchDistance = null;
      if (activePointers.size === 0) canvas.classList.remove("is-dragging");
      if (canvas.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId);
    };
    const onPointerLeave = () => {
      hovering = false;
      if (!dragging) setHoveredCountry(null);
    };
    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      rotationRef.current.scale = Math.max(
        MIN_GLOBE_SCALE,
        Math.min(MAX_GLOBE_SCALE, rotationRef.current.scale - event.deltaY * 0.0009),
      );
    };
    const onKeyDown = (event: KeyboardEvent) => {
      const step = 0.12;
      if (event.key === "ArrowLeft") rotationRef.current.yaw -= step;
      else if (event.key === "ArrowRight") rotationRef.current.yaw += step;
      else if (event.key === "ArrowUp") rotationRef.current.pitch = Math.min(1.15, rotationRef.current.pitch + step);
      else if (event.key === "ArrowDown") rotationRef.current.pitch = Math.max(-1.15, rotationRef.current.pitch - step);
      else return;
      event.preventDefault();
    };

    const resizeObserver = new ResizeObserver(resize);
    resizeObserver.observe(canvas);
    canvas.addEventListener("pointerdown", onPointerDown);
    canvas.addEventListener("pointermove", onPointerMove);
    canvas.addEventListener("pointerup", onPointerUp);
    canvas.addEventListener("pointercancel", onPointerUp);
    canvas.addEventListener("pointerleave", onPointerLeave);
    canvas.addEventListener("wheel", onWheel, { passive: false });
    canvas.addEventListener("keydown", onKeyDown);
    resize();
    frame = requestAnimationFrame(draw);

    return () => {
      cancelAnimationFrame(frame);
      resizeObserver.disconnect();
      canvas.removeEventListener("pointerdown", onPointerDown);
      canvas.removeEventListener("pointermove", onPointerMove);
      canvas.removeEventListener("pointerup", onPointerUp);
      canvas.removeEventListener("pointercancel", onPointerUp);
      canvas.removeEventListener("pointerleave", onPointerLeave);
      canvas.removeEventListener("wheel", onWheel);
      canvas.removeEventListener("keydown", onKeyDown);
    };
  }, [features, paused, servers]);

  const resetView = () => {
    rotationRef.current = { ...INITIAL_GLOBE_VIEW };
  };

  return (
    <div className="network-globe">
      <canvas
        aria-label="Интерактивный глобус с маршрутами серверов Levik VPN к центру России"
        className="network-globe__canvas"
        ref={canvasRef}
        role="img"
        tabIndex={0}
      />
      {features.length === 0 ? <div className="network-globe__loader" aria-hidden="true" /> : null}
      {hoveredCountry ? (
        <div
          className="network-globe__tooltip"
          style={{ left: hoveredCountry.x, top: hoveredCountry.y }}
        >
          <CountryFlag code={hoveredCountry.code} />
          {countryName(hoveredCountry.code)}
        </div>
      ) : null}
      <div className="network-globe__controls">
        <button
          aria-label={paused ? "Включить вращение глобуса" : "Остановить вращение глобуса"}
          className="globe-control"
          onClick={() => setPaused((current) => !current)}
          type="button"
        >
          {paused ? <PlayIcon /> : <PauseIcon />}
        </button>
        <button
          aria-label="Вернуть исходный вид глобуса"
          className="globe-control"
          onClick={resetView}
          type="button"
        >
          <GlobeIcon />
        </button>
      </div>
    </div>
  );
}

export function ServerStatusDashboard({
  initialSnapshot,
}: {
  initialSnapshot: StatusSnapshot | null;
}) {
  const [snapshot, setSnapshot] = useState(initialSnapshot);
  const [connectionState, setConnectionState] = useState<ConnectionState>(
    initialSnapshot?.source === "stale" ? "stale" : initialSnapshot ? "ok" : "loading",
  );
  const [refreshing, setRefreshing] = useState(false);

  const refresh = useCallback(async (manual = false) => {
    if (manual) setRefreshing(true);
    try {
      const response = await fetch("/api/status", { cache: "no-store" });
      if (!response.ok) throw new Error("status_unavailable");
      const payload: unknown = await response.json();
      if (!isStatusSnapshot(payload)) throw new Error("invalid_status_payload");
      setSnapshot(payload);
      setConnectionState(payload.source === "stale" ? "stale" : "ok");
    } catch {
      setConnectionState(snapshot ? "stale" : "error");
    } finally {
      if (manual) setRefreshing(false);
    }
  }, [snapshot]);

  useEffect(() => {
    if (!initialSnapshot) void refresh();
    const interval = window.setInterval(() => {
      if (document.visibilityState === "visible") void refresh();
    }, 30_000);
    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") void refresh();
    };
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => {
      window.clearInterval(interval);
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }, [initialSnapshot, refresh]);

  const servers = snapshot?.servers ?? [];
  const summary = useMemo(() => {
    const online = servers.filter((server) => server.state === "online").length;
    const countries = new Set(servers.map((server) => server.countryCode)).size;
    const traffic = servers.reduce((total, server) => total + server.trafficUsedBytes, 0);
    return { online, countries, traffic };
  }, [servers]);

  const groupedServers = useMemo(() => {
    const groups = new Map<string, PublicServerStatus[]>();
    for (const server of servers) {
      const group = groups.get(server.countryCode) ?? [];
      group.push(server);
      groups.set(server.countryCode, group);
    }
    return [...groups.entries()].sort(([left], [right]) =>
      countryName(left).localeCompare(countryName(right), "ru"),
    );
  }, [servers]);

  const allOperational = servers.length > 0 && summary.online === servers.length;

  return (
    <>
      <section className="container status-hero">
        <div className="status-hero__heading">
          <div>
            <p className="eyebrow">
              <span aria-hidden="true" className={`live-dot${allOperational ? "" : " live-dot--warning"}`} />
              Состояние сети Levik VPN
            </p>
            <h1>
              Вся сеть <strong>на одном глобусе</strong>
            </h1>
            <p>
              Актуальная доступность серверов по странам. Данные обновляются автоматически.
            </p>
          </div>
          <button
            className="button button--ghost status-refresh"
            disabled={refreshing}
            onClick={() => void refresh(true)}
            type="button"
          >
            <RefreshIcon className={refreshing ? "is-spinning" : undefined} />
            Обновить
          </button>
        </div>

        <div className="status-stage">
          <div className="status-stage__globe">
            <NetworkGlobe servers={servers} />
          </div>
          <aside className="status-overview" aria-label="Сводка по сети">
            <div className={`network-state network-state--${connectionState}`}>
              <span aria-hidden="true" />
              <div>
                <strong>
                  {connectionState === "error"
                    ? "Данные временно недоступны"
                    : allOperational
                      ? "Все системы работают"
                      : "Есть ограничения"}
                </strong>
                <small>
                  {snapshot
                    ? `Обновлено в ${formatUpdatedAt(snapshot.fetchedAt)} · ${snapshot.controlLatencyMs} мс`
                    : "Получаем актуальное состояние сети"}
                </small>
              </div>
            </div>
            <dl className="status-metrics">
              <div>
                <dt>Доступно</dt>
                <dd>{summary.online}<span> / {servers.length}</span></dd>
              </div>
              <div>
                <dt>Стран</dt>
                <dd>{summary.countries}</dd>
              </div>
              <div>
                <dt>Трафик сети</dt>
                <dd className="status-metrics__traffic">{formatBytes(summary.traffic)}</dd>
              </div>
            </dl>
            <div className="route-legend" aria-label="Обозначения статусов">
              {(Object.keys(STATE_LABELS) as ServerState[]).map((state) => (
                <span key={state}>
                  <i className={`state-dot state-dot--${state}`} />
                  {STATE_LABELS[state]}
                </span>
              ))}
            </div>
          </aside>
        </div>
      </section>

      <section className="container status-locations" aria-labelledby="locations-title">
        <div className="status-section-heading">
          <div>
            <span>География сети</span>
            <h2 id="locations-title">Серверы по странам</h2>
          </div>
          <p>{servers.length} серверов в {summary.countries} странах</p>
        </div>

        {groupedServers.length > 0 ? (
          <div className="country-grid">
            {groupedServers.map(([code, countryServers]) => (
              <article className="country-card" key={code}>
                <header className="country-card__header">
                  <span className="country-card__flag" aria-hidden="true">
                    <CountryFlag code={code} />
                  </span>
                  <div>
                    <h3>{countryName(code)}</h3>
                    <p>{countryServers.length} {countryServers.length === 1 ? "сервер" : "сервера"}</p>
                  </div>
                  <span className="country-card__availability">
                    {countryServers.filter((server) => server.state === "online").length}/{countryServers.length}
                  </span>
                </header>
                <div className="country-card__servers">
                  {countryServers.map((server) => (
                    <div className="server-row" key={server.id}>
                      <div className="server-row__identity">
                        <i className={`state-dot state-dot--${server.state}`} />
                        <strong>{STATE_LABELS[server.state]}</strong>
                      </div>
                      <dl>
                        <div>
                          <dt>Нагрузка</dt>
                          <dd>{server.load === null ? "—" : server.load.toFixed(2)}</dd>
                        </div>
                        <div>
                          <dt>Аптайм</dt>
                          <dd>{formatUptime(server.uptimeSeconds)}</dd>
                        </div>
                      </dl>
                    </div>
                  ))}
                </div>
              </article>
            ))}
          </div>
        ) : (
          <div className="status-empty">
            <GlobeIcon />
            <h3>Статус сети загружается</h3>
            <p>Если данные не появятся, обновите страницу немного позже.</p>
          </div>
        )}
      </section>
    </>
  );
}
