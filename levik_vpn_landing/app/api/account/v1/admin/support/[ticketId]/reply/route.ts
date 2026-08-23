import type { NextRequest } from "next/server";

import { enforceAccountRateLimit } from "@/lib/server/account/auth";
import { AccountApiError } from "@/lib/server/account/errors";
import {
  accountErrorResponse,
  accountJson,
  readAccountJson,
} from "@/lib/server/account/http";
import { supportReplySchema } from "@/lib/server/account/schemas";
import { authenticateSupportAdminMutation } from "@/lib/server/account/support-admin";
import { replyToSupportTicketAsStaff } from "@/lib/server/account/support";

export const dynamic = "force-dynamic";

export async function POST(
  request: NextRequest,
  context: { params: Promise<{ ticketId: string }> },
) {
  try {
    const session = await authenticateSupportAdminMutation(request);
    const ticketId = (await context.params).ticketId;
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(ticketId)) {
      throw new AccountApiError("support_ticket_not_found", 404);
    }
    const body = await readAccountJson(request, supportReplySchema);
    await enforceAccountRateLimit({
      scope: "account-support-admin-reply",
      identifier: session.userKey,
      limit: 60,
      windowSeconds: 60 * 60,
    });
    const reply = await replyToSupportTicketAsStaff({
      ticketId,
      message: body.message,
      staffUserKey: session.userKey,
    });
    return accountJson({ ok: true, reply }, { status: 201 });
  } catch (error) {
    return accountErrorResponse(error, "support_admin_unavailable");
  }
}
