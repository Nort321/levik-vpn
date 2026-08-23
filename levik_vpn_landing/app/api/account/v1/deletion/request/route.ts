import type { NextRequest } from "next/server";

import { writeAuditEvent } from "@/lib/server/audit";
import { requestAccountDeletion } from "@/lib/server/account/deletion";
import { AccountApiError } from "@/lib/server/account/errors";
import {
  accountErrorResponse,
  accountJson,
  authenticateAccountMutation,
  readAccountJson,
} from "@/lib/server/account/http";
import { emptyObjectSchema } from "@/lib/server/account/schemas";
import { isRecentlyAuthenticated } from "@/lib/server/account/session";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    const session = await authenticateAccountMutation(request);
    await readAccountJson(request, emptyObjectSchema);
    if (!isRecentlyAuthenticated(session)) {
      throw new AccountApiError("reauthentication_required", 403);
    }
    const deletion = await requestAccountDeletion(session.accountId);
    await writeAuditEvent({
      eventType: "account.deletion.request",
      outcome: "success",
      accountId: session.accountId,
    });
    return accountJson({
      ok: true,
      confirmationToken: deletion.confirmationToken,
      expiresAt: deletion.expiresAt.toISOString(),
    });
  } catch (error) {
    return accountErrorResponse(error, "deletion_unavailable");
  }
}
