import "server-only";

import { NextResponse, type NextRequest } from "next/server";
import type { z } from "zod";

import { AccountApiError } from "@/lib/server/account/errors";
import {
  csrfForAccountSession,
  requireAccountSession,
  type AuthenticatedAccountSession,
} from "@/lib/server/account/session";
import { constantTimeEqual } from "@/lib/server/crypto";
import {
  assertOriginHeader,
  RequestSecurityError,
} from "@/lib/server/security";

const MAX_ACCOUNT_JSON_BYTES = 32 * 1_024;

export const ACCOUNT_NO_STORE_HEADERS = {
  "Cache-Control": "private, no-store, max-age=0",
  Pragma: "no-cache",
  "Content-Type": "application/json; charset=utf-8",
  "X-Content-Type-Options": "nosniff",
};

export async function readAccountJson<Schema extends z.ZodType>(
  request: Request,
  schema: Schema,
): Promise<z.output<Schema>> {
  const rawLength = request.headers.get("content-length");
  const contentType = request.headers.get("content-type") ?? "";
  if (
    rawLength === null ||
    !/^[1-9][0-9]{0,5}$/.test(rawLength) ||
    Number(rawLength) > MAX_ACCOUNT_JSON_BYTES ||
    request.headers.has("transfer-encoding") ||
    !contentType.toLowerCase().startsWith("application/json")
  ) {
    throw new AccountApiError("invalid_json_request", 415);
  }
  const bytes = Buffer.from(await request.arrayBuffer());
  if (bytes.byteLength !== Number(rawLength) || bytes.byteLength > MAX_ACCOUNT_JSON_BYTES) {
    throw new AccountApiError("invalid_json_request", 400);
  }
  let value: unknown;
  try {
    value = JSON.parse(bytes.toString("utf8"));
  } catch {
    throw new AccountApiError("invalid_json_request", 400);
  }
  const parsed = schema.safeParse(value);
  if (!parsed.success) {
    throw new AccountApiError("invalid_request", 400);
  }
  return parsed.data;
}

export function assertAccountAuthRequest(request: Request): void {
  assertOriginHeader(request.headers);
}

export async function authenticateAccountMutation(
  request: NextRequest,
): Promise<AuthenticatedAccountSession> {
  assertOriginHeader(request.headers);
  const session = await requireAccountSession(request);
  const csrf = request.headers.get("x-levik-csrf") ?? "";
  if (!constantTimeEqual(csrfForAccountSession(session), csrf)) {
    throw new AccountApiError("csrf_failed", 403);
  }
  return session;
}

export function accountJson(
  body: Readonly<Record<string, unknown>>,
  init: { status?: number; headers?: HeadersInit } = {},
): NextResponse {
  return NextResponse.json(body, {
    status: init.status,
    headers: {
      ...ACCOUNT_NO_STORE_HEADERS,
      ...Object.fromEntries(new Headers(init.headers).entries()),
    },
  });
}

const ERROR_MESSAGES: Readonly<Record<string, string>> = {
  authentication_required: "Authentication is required.",
  account_not_found: "The account is unavailable.",
  credential_conflict: "This credential is already linked to another account.",
  csrf_failed: "The request could not be verified.",
  invalid_credentials: "The credentials are invalid.",
  invalid_request: "The request is invalid.",
  rate_limited: "Too many attempts. Try again later.",
  reauthentication_required: "Please sign in again before this action.",
  temporarily_unavailable: "The account service is temporarily unavailable.",
};

export function accountErrorResponse(
  error: unknown,
  fallbackCode = "temporarily_unavailable",
): NextResponse {
  const normalized =
    error instanceof AccountApiError
      ? error
      : error instanceof RequestSecurityError
        ? new AccountApiError("request_denied", error.status)
        : new AccountApiError(fallbackCode, 503, true);
  return accountJson(
    {
      ok: false,
      error: {
        code: normalized.code,
        message: ERROR_MESSAGES[normalized.code] ?? "The request could not be completed.",
        retryable: normalized.retryable,
      },
    },
    { status: normalized.status },
  );
}
