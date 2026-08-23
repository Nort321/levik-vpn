import type { NextRequest } from "next/server";

import { writeAuditEvent } from "@/lib/server/audit";
import { enforceAccountRateLimit } from "@/lib/server/account/auth";
import {
  accountErrorResponse,
  accountJson,
  authenticateAccountMutation,
  readAccountJson,
} from "@/lib/server/account/http";
import { supportCreateSchema } from "@/lib/server/account/schemas";
import {
  createSupportTicket,
  listSupportTickets,
} from "@/lib/server/account/support";
import { requireAccountSession } from "@/lib/server/account/session";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    const session = await requireAccountSession(request);
    await enforceAccountRateLimit({
      scope: "account-support-read",
      identifier: session.accountId,
      limit: 120,
      windowSeconds: 60,
    });
    return accountJson({
      ok: true,
      tickets: await listSupportTickets(session.accountId),
    });
  } catch (error) {
    return accountErrorResponse(error, "support_unavailable");
  }
}

export async function POST(request: NextRequest) {
  try {
    const session = await authenticateAccountMutation(request);
    const body = await readAccountJson(request, supportCreateSchema);
    await enforceAccountRateLimit({
      scope: "account-support-create",
      identifier: session.accountId,
      limit: 5,
      windowSeconds: 60 * 60,
    });
    const ticket = await createSupportTicket({
      accountId: session.accountId,
      category: body.category,
      subject: body.subject,
      message: body.message,
      diagnostics: body.diagnostics,
    });
    await writeAuditEvent({
      eventType: "account.support.create",
      outcome: "success",
      accountId: session.accountId,
      metadata: { ticketStatus: ticket.status },
    });
    return accountJson({ ok: true, ticket }, { status: 201 });
  } catch (error) {
    return accountErrorResponse(error, "support_unavailable");
  }
}
