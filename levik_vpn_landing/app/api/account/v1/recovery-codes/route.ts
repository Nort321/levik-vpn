import type { NextRequest } from "next/server";

import { writeAuditEvent } from "@/lib/server/audit";
import { AccountApiError } from "@/lib/server/account/errors";
import {
  accountErrorResponse,
  accountJson,
  authenticateAccountMutation,
  readAccountJson,
} from "@/lib/server/account/http";
import { regenerateRecoveryCodes } from "@/lib/server/account/recovery";
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
    const codes = await regenerateRecoveryCodes(session.accountId);
    await writeAuditEvent({
      eventType: "account.recovery.regenerate",
      outcome: "success",
      accountId: session.accountId,
    });
    return accountJson({ ok: true, codes });
  } catch (error) {
    return accountErrorResponse(error, "recovery_codes_unavailable");
  }
}
