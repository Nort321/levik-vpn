import "server-only";

import { NextResponse } from "next/server";

import { BridgeError } from "@/lib/server/bridge/core";
import {
  MobileApiError,
  MOBILE_NO_STORE_HEADERS,
} from "@/lib/server/mobile-api";

export function mobileErrorResponse(
  error: unknown,
  fallbackCode = "temporarily_unavailable",
): NextResponse {
  if (error instanceof MobileApiError) {
    return NextResponse.json(
      {
        ok: false,
        error: {
          code: error.code,
          retryable: error.retryable,
        },
      },
      {
        status: error.status,
        headers: MOBILE_NO_STORE_HEADERS,
      },
    );
  }

  if (error instanceof BridgeError) {
    if (
      ["authorization_denied", "access_denied"].includes(error.code)
    ) {
      return NextResponse.json(
        {
          ok: false,
          error: {
            code: "login_denied",
            retryable: false,
          },
        },
        { status: 403, headers: MOBILE_NO_STORE_HEADERS },
      );
    }
    if (
      ["authorization_expired", "expired_token"].includes(error.code)
    ) {
      return NextResponse.json(
        {
          ok: false,
          error: {
            code: "login_expired",
            retryable: false,
          },
        },
        { status: 410, headers: MOBILE_NO_STORE_HEADERS },
      );
    }
    return NextResponse.json(
      {
        ok: false,
        error: {
          code: fallbackCode,
          retryable: error.retryable,
        },
      },
      { status: 503, headers: MOBILE_NO_STORE_HEADERS },
    );
  }

  return NextResponse.json(
    {
      ok: false,
      error: {
        code: fallbackCode,
        retryable: true,
      },
    },
    { status: 503, headers: MOBILE_NO_STORE_HEADERS },
  );
}
