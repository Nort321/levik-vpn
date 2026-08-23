import type { NextRequest } from "next/server";

import { writeAuditEvent } from "@/lib/server/audit";
import { AccountApiError } from "@/lib/server/account/errors";
import {
  accountErrorResponse,
  accountJson,
  authenticateAccountMutation,
} from "@/lib/server/account/http";
import {
  clearAccountSessionCookie,
  revokeAccountSession,
} from "@/lib/server/account/session";

export const dynamic = "force-dynamic";

export async function DELETE(
  request: NextRequest,
  context: { params: Promise<{ sessionId: string }> },
) {
  try {
    const session = await authenticateAccountMutation(request);
    const sessionId = (await context.params).sessionId;
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(sessionId)) {
      throw new AccountApiError("session_not_found", 404);
    }
    if (!(await revokeAccountSession(session.accountId, sessionId))) {
      throw new AccountApiError("session_not_found", 404);
    }
    await writeAuditEvent({
      eventType: "account.session.revoke",
      outcome: "success",
      accountId: session.accountId,
    });
    const response = accountJson({ ok: true });
    if (session.publicId === sessionId) {
      clearAccountSessionCookie(response);
    }
    return response;
  } catch (error) {
    return accountErrorResponse(error, "session_revoke_unavailable");
  }
}
