import type { NextRequest } from "next/server";

import { writeAuditEvent } from "@/lib/server/audit";
import { enforceAccountRateLimit } from "@/lib/server/account/auth";
import { AccountApiError } from "@/lib/server/account/errors";
import {
  accountErrorResponse,
  accountJson,
  authenticateAccountMutation,
  readAccountJson,
} from "@/lib/server/account/http";
import { supportReplySchema } from "@/lib/server/account/schemas";
import { replyToSupportTicket } from "@/lib/server/account/support";

export const dynamic = "force-dynamic";

export async function POST(
  request: NextRequest,
  context: { params: Promise<{ ticketId: string }> },
) {
  try {
    const session = await authenticateAccountMutation(request);
    const ticketId = (await context.params).ticketId;
    if (!/^[0-9a-f-]{36}$/i.test(ticketId)) {
      throw new AccountApiError("support_ticket_not_found", 404);
    }
    const body = await readAccountJson(request, supportReplySchema);
    await enforceAccountRateLimit({
      scope: "account-support-reply",
      identifier: session.accountId,
      limit: 30,
      windowSeconds: 60 * 60,
    });
    const reply = await replyToSupportTicket({
      accountId: session.accountId,
      ticketId,
      message: body.message,
    });
    await writeAuditEvent({
      eventType: "account.support.reply",
      outcome: "success",
      accountId: session.accountId,
    });
    return accountJson({ ok: true, reply }, { status: 201 });
  } catch (error) {
    return accountErrorResponse(error, "support_unavailable");
  }
}
