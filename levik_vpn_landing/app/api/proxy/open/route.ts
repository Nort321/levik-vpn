import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import { getOptionalSession } from "@/lib/server/browser-auth";
import { getEphemeralCredential } from "@/lib/server/credential-store";
import { assertSameOriginNavigation } from "@/lib/server/route-auth";

export const dynamic = "force-dynamic";

function isCanonicalTelegramProxy(value: string): boolean {
  try {
    const url = new URL(value);
    const keys = [...url.searchParams.keys()];
    const port = Number(url.searchParams.get("port"));
    const server = url.searchParams.get("server") ?? "";
    const secret = url.searchParams.get("secret") ?? "";
    return (
      url.protocol === "tg:" &&
      url.hostname === "proxy" &&
      url.pathname === "" &&
      !url.username &&
      !url.password &&
      !url.hash &&
      keys.length === 3 &&
      new Set(keys).size === 3 &&
      Number.isInteger(port) &&
      port >= 1 &&
      port <= 65_535 &&
      /^[A-Za-z0-9.-]{1,253}$/.test(server) &&
      /^[A-Za-z0-9_-]{8,512}$/.test(secret)
    );
  } catch {
    return false;
  }
}

export async function GET(request: NextRequest) {
  try {
    assertSameOriginNavigation(request);
    const session = await getOptionalSession();
    if (!session) {
      return NextResponse.redirect(new URL("/login", request.url), 303);
    }
    const proxyUrl = await getEphemeralCredential(
      session.userKey,
      "free_proxy",
    );
    if (!proxyUrl || !isCanonicalTelegramProxy(proxyUrl)) {
      throw new Error("proxy_unavailable");
    }
    return new NextResponse(null, {
      status: 303,
      headers: {
        "Cache-Control": "private, no-store",
        Location: proxyUrl,
        "Referrer-Policy": "no-referrer",
      },
    });
  } catch {
    return NextResponse.redirect(new URL("/dashboard", request.url), 303);
  }
}
