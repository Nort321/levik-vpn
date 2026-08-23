import type { NextRequest } from "next/server";

import { enforceAccountRateLimit } from "@/lib/server/account/auth";
import { AccountApiError } from "@/lib/server/account/errors";
import {
  accountErrorResponse,
  accountJson,
  readAccountJson,
} from "@/lib/server/account/http";
import { supportStatusMutationSchema } from "@/lib/server/account/schemas";
import { authenticateSupportAdminMutation } from "@/lib/server/account/support-admin";
import { setSupportTicketStatusAsStaff } from "@/lib/server/account/support";

export const dynamic = "force-dynamic";

export async function PATCH(
  request: NextRequest,
  context: { params: Promise<{ ticketId: string }> },
) {
  try {
    const session = await authenticateSupportAdminMutation(request);
    const ticketId = (await context.params).ticketId;
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(ticketId)) {
      throw new AccountApiError("support_ticket_not_found", 404);
    }
    const body = await readAccountJson(request, supportStatusMutationSchema);
    await enforceAccountRateLimit({
      scope: "account-support-admin-status",
      identifier: session.userKey,
      limit: 120,
      windowSeconds: 60 * 60,
    });
    await setSupportTicketStatusAsStaff(
      ticketId,
      body.status,
      session.userKey,
    );
    return accountJson({ ok: true, status: body.status });
  } catch (error) {
    return accountErrorResponse(error, "support_admin_unavailable");
  }
}
