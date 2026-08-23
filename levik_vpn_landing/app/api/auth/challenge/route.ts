import { NextResponse } from "next/server";

import {
  LOGIN_COOKIE_NAME,
  loginCookieOptions,
} from "@/lib/server/browser-auth";
import { beginDeviceLogin } from "@/lib/server/login-service";
import { consumeRateLimit } from "@/lib/server/rate-limit";
import {
  assertOriginHeader,
  clientAddressFromHeaders,
  RequestSecurityError,
} from "@/lib/server/security";

export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  try {
    assertOriginHeader(request.headers);
    const clientAddress = clientAddressFromHeaders(request.headers);
    const limit = await consumeRateLimit({
      scope: "auth-start-ip",
      identifier: clientAddress,
      limit: 10,
      windowSeconds: 10 * 60,
    });
    if (!limit.allowed) {
      return NextResponse.json(
        { ok: false, code: "rate_limited" },
        {
          status: 429,
          headers: {
            "Cache-Control": "no-store",
            "Retry-After": limit.retryAfterSeconds.toString(),
          },
        },
      );
    }

    const attempt = await beginDeviceLogin();
    const response = NextResponse.json(
      { ok: true, redirectTo: "/login" },
      {
        status: 201,
        headers: {
          "Cache-Control": "no-store",
        },
      },
    );
    response.cookies.set(
      LOGIN_COOKIE_NAME,
      attempt.browserToken,
      loginCookieOptions,
    );
    return response;
  } catch (error) {
    const status = error instanceof RequestSecurityError ? error.status : 503;
    return NextResponse.json(
      { ok: false, code: status === 503 ? "temporarily_unavailable" : "denied" },
      {
        status,
        headers: {
          "Cache-Control": "no-store",
        },
      },
    );
  }
}
