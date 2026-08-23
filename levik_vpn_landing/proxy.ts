import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

const MAIN_HOSTS = new Set(["leviknet.com", "www.leviknet.com"]);
const CHECK_HOST = "check.leviknet.com";
const NOTE_HOST = "note.leviknet.com";
const MONITOR_HOST = "mon.leviknet.com";
const CANONICAL_HOSTS = new Set([...MAIN_HOSTS, CHECK_HOST, NOTE_HOST, MONITOR_HOST]);

export function proxy(request: NextRequest) {
  if (request.nextUrl.pathname === "/api/health") {
    return NextResponse.next();
  }

  const forwardedHost = request.headers.get("x-forwarded-host");
  const host = (forwardedHost ?? request.headers.get("host") ?? "")
    .split(":")[0]
    .toLowerCase();

  if (process.env.NODE_ENV === "production" && !CANONICAL_HOSTS.has(host)) {
    return new NextResponse(null, { status: 421 });
  }

  const isCheckHost = host === CHECK_HOST;
  const isNoteHost = host === NOTE_HOST;
  const isMonitorHost = host === MONITOR_HOST;
  if (MAIN_HOSTS.has(host) && request.nextUrl.pathname.startsWith("/monitor")) {
    const monitorPath = request.nextUrl.pathname.replace(/^\/monitor/, "") || "/";
    return NextResponse.redirect(
      new URL(`${monitorPath}${request.nextUrl.search}`, `https://${MONITOR_HOST}`),
      308,
    );
  }
  if (isMonitorHost && request.nextUrl.pathname.startsWith("/monitor")) {
    const monitorPath = request.nextUrl.pathname.replace(/^\/monitor/, "") || "/";
    return NextResponse.redirect(
      new URL(`${monitorPath}${request.nextUrl.search}`, `https://${MONITOR_HOST}`),
      308,
    );
  }
  if (
    isMonitorHost &&
    request.nextUrl.pathname.startsWith("/api/") &&
    !request.nextUrl.pathname.startsWith("/api/monitor/")
  ) {
    return new NextResponse(null, { status: 404 });
  }
  if (isCheckHost && request.nextUrl.pathname === "/check") {
    return NextResponse.redirect(new URL("https://check.leviknet.com/"), 308);
  }
  if (
    isCheckHost &&
    request.nextUrl.pathname !== "/" &&
    !request.nextUrl.pathname.startsWith("/api/check")
  ) {
    if (request.nextUrl.pathname.startsWith("/api/")) {
      return new NextResponse(null, { status: 404 });
    }
    return NextResponse.redirect(
      new URL(`${request.nextUrl.pathname}${request.nextUrl.search}`, "https://leviknet.com"),
      308,
    );
  }
  if (MAIN_HOSTS.has(host) && request.nextUrl.pathname === "/check") {
    return NextResponse.redirect(new URL("https://check.leviknet.com/"), 308);
  }
  if (MAIN_HOSTS.has(host) && request.nextUrl.pathname.startsWith("/notes")) {
    return NextResponse.redirect(
      new URL(request.nextUrl.pathname.replace(/^\/notes/, "") || "/", "https://note.leviknet.com"),
      308,
    );
  }

  let noteRewritePath: string | null = null;
  if (isNoteHost) {
    if (request.nextUrl.pathname === "/") {
      noteRewritePath = "/notes";
    } else if (/^\/[A-Za-z0-9_-]{22}$/.test(request.nextUrl.pathname)) {
      noteRewritePath = `/notes${request.nextUrl.pathname}`;
    } else if (!request.nextUrl.pathname.startsWith("/api/notes")) {
      if (request.nextUrl.pathname.startsWith("/api/")) {
        return new NextResponse(null, { status: 404 });
      }
      return NextResponse.redirect(new URL("https://note.leviknet.com/"), 308);
    }
  }

  const nonce = Buffer.from(crypto.randomUUID()).toString("base64");
  const isDevelopment = process.env.NODE_ENV === "development";
  const usesGoogleIdentity =
    request.nextUrl.pathname === "/login" ||
    request.nextUrl.pathname === "/activate" ||
    request.nextUrl.pathname === "/dashboard/identities";
  const googleIdentitySource = usesGoogleIdentity
    ? " https://accounts.google.com"
    : "";
  const contentSecurityPolicy = [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${googleIdentitySource}${
      isDevelopment ? " 'unsafe-eval'" : ""
    }`,
    `style-src 'self' 'nonce-${nonce}'${googleIdentitySource}`,
    "img-src 'self' blob: data:",
    "font-src 'self'",
    `connect-src 'self'${googleIdentitySource} https://api6.ipify.org https://www.google.com https://www.youtube.com https://i.ytimg.com https://telegram.org https://web.telegram.org https://www.whatsapp.com https://web.whatsapp.com https://discord.com https://cdn.discordapp.com https://store.steampowered.com https://steamcommunity.com https://store.cloudflare.steamstatic.com https://github.com https://api.github.com https://github.githubassets.com https://www.tiktok.com https://lf16-tiktok-web.ttwstatic.com https://www.twitch.tv https://gql.twitch.tv https://static.twitchcdn.net https://vk.com stun:`,
    `frame-src 'self'${googleIdentitySource}`,
    "media-src 'self'",
    "manifest-src 'self'",
    "worker-src 'self' blob:",
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'self'",
    "frame-ancestors 'none'",
    "upgrade-insecure-requests",
  ].join("; ");

  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("x-nonce", nonce);
  requestHeaders.set("Content-Security-Policy", contentSecurityPolicy);

  const rewritePath = isCheckHost && request.nextUrl.pathname === "/"
    ? "/check"
    : isMonitorHost && !request.nextUrl.pathname.startsWith("/api/monitor/")
      ? `/monitor${request.nextUrl.pathname === "/" ? "" : request.nextUrl.pathname}`
      : noteRewritePath;
  const response = rewritePath
    ? NextResponse.rewrite(new URL(rewritePath, request.url), {
        request: { headers: requestHeaders },
      })
    : NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set("Content-Security-Policy", contentSecurityPolicy);
  response.headers.set(
    "Cross-Origin-Opener-Policy",
    usesGoogleIdentity ? "same-origin-allow-popups" : "same-origin",
  );
  response.headers.set("Cross-Origin-Resource-Policy", "same-origin");

  return response;
}

export const config = {
  matcher: [
    {
      source:
        "/((?!_next/static|_next/image|api/admin/updates/upload|favicon.ico|robots.txt|sitemap.xml|.*\\.(?:svg|png|jpg|jpeg|gif|webp|avif|ico)$).*)",
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" },
      ],
    },
  ],
};
