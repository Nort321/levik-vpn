import "server-only";

import type { NextRequest } from "next/server";

import { writeAuditEvent } from "@/lib/server/audit";
import { accountJson } from "@/lib/server/account/http";
import { publicAccount, type AccountRecord } from "@/lib/server/account/model";
import {
  createAccountSession,
  csrfForAccountSession,
  setAccountSessionCookie,
  type AccountAuthMethod,
} from "@/lib/server/account/session";
import { clientAddressFromHeaders } from "@/lib/server/security";
import { consumeRateLimit } from "@/lib/server/rate-limit";
import { AccountApiError } from "@/lib/server/account/errors";

export async function finishAccountAuthentication(input: {
  request: NextRequest;
  account: AccountRecord;
  authMethod: AccountAuthMethod;
  deviceName?: string;
}) {
  const session = await createAccountSession(input.account.accountId, input.authMethod, {
    deviceName: input.deviceName,
    userAgent: input.request.headers.get("user-agent"),
    clientAddress: clientAddressFromHeaders(input.request.headers),
  });
  await writeAuditEvent({
    eventType: "account.auth.login",
    outcome: "success",
    accountId: input.account.accountId,
    metadata: { authMethod: input.authMethod },
  });
  const response = accountJson({
    ok: true,
    account: publicAccount(input.account),
    session: {
      id: session.publicId,
      expiresAt: session.absoluteExpiresAt.toISOString(),
    },
    csrfToken: csrfForAccountSession(session),
  });
  setAccountSessionCookie(response, session.rawToken);
  return response;
}

export async function enforceAccountRateLimit(input: {
  scope: string;
  identifier: string;
  limit: number;
  windowSeconds: number;
}): Promise<void> {
  const result = await consumeRateLimit(input);
  if (!result.allowed) {
    throw new AccountApiError("rate_limited", 429, true);
  }
}
