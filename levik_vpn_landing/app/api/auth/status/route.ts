import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import {
  LOGIN_COOKIE_NAME,
  loginCookieOptions,
  SESSION_COOKIE_NAME,
  sessionCookieOptions,
} from "@/lib/server/browser-auth";
import { ensureLegacyAccount } from "@/lib/server/account/legacy";
import { setAccountSessionCookie } from "@/lib/server/account/session";
import {
  getDeviceAuthorizationStatus,
} from "@/lib/server/bridge/auth";
import { BridgeError } from "@/lib/server/bridge/core";
import { consumeRateLimit } from "@/lib/server/rate-limit";
import {
  assertOriginHeader,
  clientAddressFromHeaders,
  RequestSecurityError,
} from "@/lib/server/security";
import {
  claimLoginAttemptForPolling,
  createSessionFromAuthorization,
  getLoginAttempt,
  releaseLoginPoll,
} from "@/lib/server/session-store";
import { writeAuditEvent } from "@/lib/server/audit";

export const dynamic = "force-dynamic";

const NO_STORE_HEADERS = {
  "Cache-Control": "private, no-store, max-age=0",
  "Content-Type": "application/json; charset=utf-8",
};

function clearLoginCookie(response: NextResponse): void {
  response.cookies.set(LOGIN_COOKIE_NAME, "", {
    ...loginCookieOptions,
    maxAge: 0,
  });
}

export async function POST(request: NextRequest) {
  const loginToken = request.cookies.get(LOGIN_COOKIE_NAME)?.value;

  try {
    assertOriginHeader(request.headers);
    const contentType = request.headers.get("content-type") ?? "";
    const contentLength = Number(request.headers.get("content-length") ?? "0");
    if (
      (contentLength > 0 &&
        !contentType.toLowerCase().startsWith("application/json")) ||
      contentLength > 1_024
    ) {
      return NextResponse.json(
        { state: "error", message: "Некорректный запрос." },
        { status: 415, headers: NO_STORE_HEADERS },
      );
    }
    const clientAddress = clientAddressFromHeaders(request.headers);
    const limit = await consumeRateLimit({
      scope: "auth-poll-ip",
      identifier: clientAddress,
      limit: 240,
      windowSeconds: 10 * 60,
    });
    if (!limit.allowed) {
      return NextResponse.json(
        {
          state: "error",
          message: "Слишком много проверок. Повторите немного позже.",
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

    if (!loginToken) {
      return NextResponse.json(
        {
          state: "expired",
          message: "Запрос на вход истёк. Создайте новый код.",
        },
        { status: 410, headers: NO_STORE_HEADERS },
      );
    }

    const attempt = await claimLoginAttemptForPolling(loginToken);
    if (!attempt) {
      const existingAttempt = await getLoginAttempt(loginToken);
      return NextResponse.json(
        existingAttempt
          ? {
              state: "pending",
            }
          : {
              state: "expired",
              message: "Запрос на вход истёк. Создайте новый код.",
            },
        {
          status: existingAttempt ? 200 : 410,
          headers: NO_STORE_HEADERS,
        },
      );
    }

    if (attempt.provider !== "legacy_bridge") {
      await releaseLoginPoll(loginToken);
      return NextResponse.json(
        {
          state: "expired",
          message: "Этот способ входа недоступен в браузере.",
        },
        { status: 410, headers: NO_STORE_HEADERS },
      );
    }

    const status = await getDeviceAuthorizationStatus(attempt.deviceCode);
    if (status.status === "authorization_pending") {
      await releaseLoginPoll(loginToken);
      return NextResponse.json(
        {
          state: "pending",
        },
        { headers: NO_STORE_HEADERS },
      );
    }

    const session = await createSessionFromAuthorization(loginToken, status, {
      userAgent: request.headers.get("user-agent"),
      clientAddress,
    });
    if (!session) {
      return NextResponse.json(
        {
          state: "expired",
          message: "Запрос уже использован или истёк.",
        },
        { status: 409, headers: NO_STORE_HEADERS },
      );
    }

    const accountId = await ensureLegacyAccount(session).catch(() => null);

    await writeAuditEvent({
      eventType: "auth.login",
      outcome: "success",
      accountId: accountId ?? undefined,
      userKey: session.userKey,
    });
    const response = NextResponse.json(
      { state: "authenticated", redirectTo: "/dashboard" },
      { headers: NO_STORE_HEADERS },
    );
    response.cookies.set(
      SESSION_COOKIE_NAME,
      session.rawToken,
      sessionCookieOptions,
    );
    if (accountId) {
      setAccountSessionCookie(response, session.rawToken);
    }
    clearLoginCookie(response);
    return response;
  } catch (error) {
    if (loginToken) {
      await releaseLoginPoll(loginToken).catch(() => {});
    }

    if (
      error instanceof BridgeError &&
      ["authorization_denied", "access_denied"].includes(error.code)
    ) {
      const response = NextResponse.json(
        {
          state: "expired",
          message: "Вход отклонён в Telegram.",
        },
        { status: 403, headers: NO_STORE_HEADERS },
      );
      clearLoginCookie(response);
      return response;
    }
    if (
      error instanceof BridgeError &&
      ["authorization_expired", "expired_token"].includes(error.code)
    ) {
      const response = NextResponse.json(
        {
          state: "expired",
          message: "Запрос на вход истёк. Создайте новый код.",
        },
        { status: 410, headers: NO_STORE_HEADERS },
      );
      clearLoginCookie(response);
      return response;
    }

    const status = error instanceof RequestSecurityError ? error.status : 503;
    return NextResponse.json(
      {
        state: "error",
        message:
          status === 503
            ? "Сервис входа временно недоступен. Повторим автоматически."
            : "Запрос отклонён.",
      },
      { status, headers: NO_STORE_HEADERS },
    );
  }
}
