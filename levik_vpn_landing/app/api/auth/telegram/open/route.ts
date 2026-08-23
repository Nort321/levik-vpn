import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import { LOGIN_COOKIE_NAME } from "@/lib/server/browser-auth";
import { getEnvironment } from "@/lib/server/env";
import { assertSameOriginNavigation } from "@/lib/server/route-auth";
import { getLoginAttempt } from "@/lib/server/session-store";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    assertSameOriginNavigation(request);
  } catch {
    return NextResponse.redirect(new URL("/login", request.url), 303);
  }

  const loginToken = request.cookies.get(LOGIN_COOKIE_NAME)?.value;
  const attempt = loginToken ? await getLoginAttempt(loginToken) : null;
  if (!attempt || attempt.provider !== "legacy_bridge") {
    return NextResponse.redirect(new URL("/login", request.url), 303);
  }

  let target: URL;
  try {
    target = new URL(attempt.verificationUriComplete);
  } catch {
    return NextResponse.redirect(new URL("/login", request.url), 303);
  }

  const expectedBotPath = `/${getEnvironment().TELEGRAM_BOT_USERNAME.toLowerCase()}`;
  if (
    target.protocol !== "https:" ||
    target.hostname.toLowerCase() !== "t.me" ||
    target.port ||
    target.username ||
    target.password ||
    target.pathname.toLowerCase() !== expectedBotPath ||
    !target.searchParams.get("start")?.startsWith("web_")
  ) {
    return NextResponse.redirect(new URL("/login", request.url), 303);
  }

  return new NextResponse(null, {
    status: 303,
    headers: {
      "Cache-Control": "private, no-store",
      Location: target.toString(),
      "Referrer-Policy": "no-referrer",
    },
  });
}
