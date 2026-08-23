import { NextResponse } from "next/server";
import { z } from "zod";

import { getEnvironment } from "@/lib/server/env";
import { consumeRateLimit } from "@/lib/server/rate-limit";
import {
  assertOriginHeader,
  clientAddressFromHeaders,
  RequestSecurityError,
} from "@/lib/server/security";

export const dynamic = "force-dynamic";

const upstreamResponseSchema = z.object({
  link: z.string().min(1).max(2_048),
  device_limit: z.number().int().min(1).max(100),
  rate_limit_mbps: z.number().int().min(1).max(10_000),
});

const NO_STORE_HEADERS = {
  "Cache-Control": "private, no-store, max-age=0",
  "Referrer-Policy": "no-referrer",
};

const FREE_PROXY_REQUESTS_PER_DAY = 60;

function canonicalTelegramProxyUrl(value: string): string | null {
  try {
    const url = new URL(value);
    const keys = [...url.searchParams.keys()];
    const server = url.searchParams.get("server") ?? "";
    const port = Number(url.searchParams.get("port"));
    const secret = url.searchParams.get("secret") ?? "";
    const hostname = url.hostname.toLowerCase();

    if (
      url.protocol !== "https:" ||
      !["t.me", "telegram.me"].includes(hostname) ||
      url.pathname !== "/proxy" ||
      url.username ||
      url.password ||
      url.hash ||
      keys.length !== 3 ||
      new Set(keys).size !== 3 ||
      !keys.every((key) => ["server", "port", "secret"].includes(key)) ||
      !/^[A-Za-z0-9.-]{1,253}$/.test(server) ||
      !Number.isInteger(port) ||
      port < 1 ||
      port > 65_535 ||
      !/^[A-Za-z0-9_-]{8,512}$/.test(secret)
    ) {
      return null;
    }

    const search = new URLSearchParams({
      server,
      port: port.toString(),
      secret,
    });
    return `tg://proxy?${search.toString()}`;
  } catch {
    return null;
  }
}

export async function POST(request: Request) {
  try {
    assertOriginHeader(request.headers);
    const environment = getEnvironment();
    if (
      !environment.FEATURE_FREE_PROXY_ENABLED ||
      !environment.SITE_FREE_PROXY_TOKEN
    ) {
      return NextResponse.json(
        {
          ok: false,
          message: "Бесплатный proxy временно недоступен.",
        },
        { status: 503, headers: NO_STORE_HEADERS },
      );
    }

    const clientAddress = clientAddressFromHeaders(request.headers);
    const limit = await consumeRateLimit({
      scope: "public-free-proxy-ip",
      identifier: clientAddress,
      limit: FREE_PROXY_REQUESTS_PER_DAY,
      windowSeconds: 24 * 60 * 60,
    });
    if (!limit.allowed) {
      return NextResponse.json(
        {
          ok: false,
          message: "Лимит запросов исчерпан. Уже выданный proxy остаётся активным.",
        },
        {
          status: 429,
          headers: {
            ...NO_STORE_HEADERS,
            "Retry-After": limit.retryAfterSeconds.toString(),
          },
        },
      );
    }

    const upstream = await fetch(environment.SITE_FREE_PROXY_URL, {
      method: "POST",
      cache: "no-store",
      redirect: "error",
      headers: {
        Accept: "application/json",
        Authorization: `Bearer ${environment.SITE_FREE_PROXY_TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ client_ip: clientAddress }),
      signal: AbortSignal.timeout(35_000),
    });

    if (!upstream.ok) {
      throw new Error("free_proxy_upstream_error");
    }

    const parsed = upstreamResponseSchema.safeParse(await upstream.json());
    if (!parsed.success) {
      throw new Error("invalid_free_proxy_response");
    }
    const link = canonicalTelegramProxyUrl(parsed.data.link);
    if (!link) {
      throw new Error("invalid_free_proxy_link");
    }

    return NextResponse.json(
      {
        ok: true,
        link,
        deviceLimit: parsed.data.device_limit,
        rateLimitMbps: parsed.data.rate_limit_mbps,
      },
      { headers: NO_STORE_HEADERS },
    );
  } catch (error) {
    const status = error instanceof RequestSecurityError ? error.status : 503;
    return NextResponse.json(
      {
        ok: false,
        message:
          status === 503
            ? "Proxy сейчас недоступен. Попробуйте немного позже."
            : "Запрос отклонён.",
      },
      { status, headers: NO_STORE_HEADERS },
    );
  }
}
