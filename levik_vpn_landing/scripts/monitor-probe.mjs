import { createHmac, randomUUID } from "node:crypto";
import { lookup } from "node:dns/promises";
import { readFile } from "node:fs/promises";
import http from "node:http";
import https from "node:https";
import net from "node:net";
import process from "node:process";
import tls from "node:tls";

const AGENT_VERSION = "1.0.0";
const INTERVAL_MS = 60_000;
const NETWORK_TIMEOUT_MS = 8_000;
const catalogUrl = new URL("../config/monitor-services.json", import.meta.url);
const services = JSON.parse(await readFile(catalogUrl, "utf8"));

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
}

const ingestUrl = new URL(required("MONITOR_INGEST_URL"));
if (ingestUrl.protocol !== "https:" && ingestUrl.hostname !== "app") {
  throw new Error("MONITOR_INGEST_URL must use HTTPS");
}
const probe = Object.freeze({
  id: required("MONITOR_PROBE_ID"),
  label: required("MONITOR_PROBE_LABEL"),
  countryCode: required("MONITOR_PROBE_COUNTRY"),
  region: process.env.MONITOR_PROBE_REGION || null,
  agentVersion: AGENT_VERSION,
});
if (!/^[a-z0-9][a-z0-9_-]{2,39}$/.test(probe.id)) {
  throw new Error("MONITOR_PROBE_ID is invalid");
}
if (!/^[A-Z]{2}$/.test(probe.countryCode)) {
  throw new Error("MONITOR_PROBE_COUNTRY must be an ISO 3166-1 alpha-2 code");
}
const secret = required("MONITOR_PROBE_SECRET");
if (
  !/^[A-Za-z0-9_-]{43}$/.test(secret) ||
  Buffer.from(secret, "base64url").byteLength !== 32
) {
  throw new Error("MONITOR_PROBE_SECRET must be a 32-byte base64url secret");
}

function elapsed(startedAt) {
  return Math.max(0, Math.round(performance.now() - startedAt));
}

async function dnsCheck(host) {
  const startedAt = performance.now();
  try {
    const addresses = await Promise.race([
      lookup(host, { all: true, verbatim: true }),
      new Promise((_, reject) =>
        setTimeout(() => reject(new Error("dns_timeout")), NETWORK_TIMEOUT_MS),
      ),
    ]);
    return { ok: addresses.length > 0, latencyMs: elapsed(startedAt) };
  } catch (error) {
    return {
      ok: false,
      latencyMs: elapsed(startedAt),
      error: error instanceof Error ? error.name : "UnknownError",
    };
  }
}

async function tcpCheck(host, port = 443) {
  const startedAt = performance.now();
  return new Promise((resolve) => {
    const socket = net.connect({ host, port });
    const finish = (result) => {
      socket.destroy();
      resolve({ ...result, latencyMs: elapsed(startedAt) });
    };
    socket.setTimeout(NETWORK_TIMEOUT_MS, () => finish({ ok: false, error: "TimeoutError" }));
    socket.once("connect", () => finish({ ok: true }));
    socket.once("error", (error) => finish({ ok: false, error: error.name }));
  });
}

async function tlsCheck(host, port = 443) {
  const startedAt = performance.now();
  return new Promise((resolve) => {
    const socket = tls.connect({
      host,
      port,
      servername: host,
      rejectUnauthorized: true,
    });
    const finish = (result) => {
      socket.destroy();
      resolve({ ...result, latencyMs: elapsed(startedAt) });
    };
    socket.setTimeout(NETWORK_TIMEOUT_MS, () => finish({ ok: false, error: "TimeoutError" }));
    socket.once("secureConnect", () =>
      finish({ ok: socket.authorized, protocol: socket.getProtocol() }),
    );
    socket.once("error", (error) => finish({ ok: false, error: error.name }));
  });
}

async function httpCheck(url) {
  const startedAt = performance.now();
  try {
    const response = await fetch(url, {
      method: "GET",
      headers: {
        Accept: "*/*",
        "User-Agent": "Levik-Monitor/1.0 (+https://mon.leviknet.com/methodology)",
      },
      cache: "no-store",
      credentials: "omit",
      redirect: "follow",
      signal: AbortSignal.timeout(NETWORK_TIMEOUT_MS),
    });
    const latencyMs = elapsed(startedAt);
    await response.body?.cancel();
    return {
      ok: response.status >= 200 && response.status < 500,
      status: response.status,
      latencyMs,
    };
  } catch (error) {
    return {
      ok: false,
      status: null,
      latencyMs: elapsed(startedAt),
      error: error instanceof Error ? error.name : "UnknownError",
    };
  }
}

async function checkService(service) {
  const dns = await dnsCheck(service.host);
  if (!dns.ok) {
    return {
      serviceSlug: service.slug,
      state: "outage",
      availability: 0,
      latencyMs: null,
      checks: { dns },
    };
  }
  const tcp = await tcpCheck(service.host);
  if (!tcp.ok) {
    return {
      serviceSlug: service.slug,
      state: "outage",
      availability: 20,
      latencyMs: tcp.latencyMs,
      checks: { dns, tcp },
    };
  }
  const tlsResult = await tlsCheck(service.host);
  if (!tlsResult.ok) {
    return {
      serviceSlug: service.slug,
      state: "outage",
      availability: 40,
      latencyMs: tlsResult.latencyMs,
      checks: { dns, tcp, tls: tlsResult },
    };
  }

  const [homepage, ...auxiliary] = await Promise.all([
    httpCheck(service.homepageUrl),
    ...service.checks.map((check) => httpCheck(check.url)),
  ]);
  const auxChecks = Object.fromEntries(
    service.checks.map((check, index) => [check.id, auxiliary[index]]),
  );
  const outcomes = [dns.ok, tcp.ok, tlsResult.ok, homepage.ok, ...auxiliary.map((check) => check.ok)];
  const availability = Math.round((outcomes.filter(Boolean).length / outcomes.length) * 100);
  const latencyMs = Math.max(
    dns.latencyMs,
    tcp.latencyMs,
    tlsResult.latencyMs,
    homepage.latencyMs,
    ...auxiliary.map((check) => check.latencyMs),
  );
  const state = !homepage.ok
    ? "outage"
    : availability < 100 || latencyMs > 2_500
      ? "degraded"
      : "operational";
  return {
    serviceSlug: service.slug,
    state,
    availability,
    latencyMs,
    checks: { dns, tcp, tls: tlsResult, http: homepage, ...auxChecks },
  };
}

async function runCycle() {
  const measuredAt = new Date().toISOString();
  const results = await Promise.all(services.map(checkService));
  const body = JSON.stringify({
    batchId: randomUUID(),
    measuredAt,
    probe,
    results,
  });
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const signature = createHmac("sha256", Buffer.from(secret, "base64url"))
    .update(`${timestamp}.${body}`)
    .digest("base64url");
  const status = await new Promise((resolve, reject) => {
    const client = ingestUrl.protocol === "https:" ? https : http;
    const request = client.request(
      ingestUrl,
      {
        method: "POST",
        headers: {
          ...(ingestUrl.hostname === "app" ? { Host: "mon.leviknet.com" } : {}),
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(body).toString(),
          "X-Levik-Probe": probe.id,
          "X-Levik-Signature": signature,
          "X-Levik-Timestamp": timestamp,
        },
      },
      (response) => {
        response.resume();
        response.once("end", () => resolve(response.statusCode ?? 0));
      },
    );
    request.setTimeout(20_000, () => request.destroy(new Error("ingest_timeout")));
    request.once("error", reject);
    request.end(body);
  });
  if (status < 200 || status >= 300) {
    throw new Error(`ingest_http_${status}`);
  }
}

async function loop() {
  const startedAt = Date.now();
  try {
    await runCycle();
  } catch (error) {
    console.error("Monitor probe cycle failed", {
      probeId: probe.id,
      errorName: error instanceof Error ? error.name : "UnknownError",
      errorCode: error instanceof Error ? error.message : "unknown_error",
    });
  } finally {
    const delay = Math.max(5_000, INTERVAL_MS - (Date.now() - startedAt));
    setTimeout(loop, delay);
  }
}

await loop();
