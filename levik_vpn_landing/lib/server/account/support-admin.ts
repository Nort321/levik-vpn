import "server-only";

import type { NextRequest } from "next/server";

import { AccountApiError } from "@/lib/server/account/errors";
import { SESSION_COOKIE_NAME } from "@/lib/server/browser-auth";
import { constantTimeEqual } from "@/lib/server/crypto";
import { getEnvironment } from "@/lib/server/env";
import { assertOriginHeader, csrfTokenForSession } from "@/lib/server/security";
import { getSessionByToken, type AuthenticatedSession } from "@/lib/server/session-store";

export async function requireSupportAdmin(
  request: NextRequest,
): Promise<AuthenticatedSession> {
  const rawToken = request.cookies.get(SESSION_COOKIE_NAME)?.value;
  const session = rawToken ? await getSessionByToken(rawToken) : null;
  if (!session) {
    throw new AccountApiError("authentication_required", 401);
  }
  if (!getEnvironment().adminUserKeys.has(session.userKey)) {
    throw new AccountApiError("access_denied", 403);
  }
  return session;
}

export async function authenticateSupportAdminMutation(
  request: NextRequest,
): Promise<AuthenticatedSession> {
  assertOriginHeader(request.headers);
  const session = await requireSupportAdmin(request);
  const supplied = request.headers.get("x-levik-csrf") ?? "";
  if (!constantTimeEqual(csrfTokenForSession(session.rawToken), supplied)) {
    throw new AccountApiError("csrf_failed", 403);
  }
  return session;
}

export function csrfForSupportAdmin(session: AuthenticatedSession): string {
  return csrfTokenForSession(session.rawToken);
}
