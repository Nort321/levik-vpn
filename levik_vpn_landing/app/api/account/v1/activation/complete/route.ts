import type { NextRequest } from "next/server";

import { writeAuditEvent } from "@/lib/server/audit";
import { authorizeAccountActivation } from "@/lib/server/account/activation";
import { enforceAccountRateLimit } from "@/lib/server/account/auth";
import {
  accountErrorResponse,
  accountJson,
  authenticateAccountMutation,
  readAccountJson,
} from "@/lib/server/account/http";
import { activationCompleteSchema } from "@/lib/server/account/schemas";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    const session = await authenticateAccountMutation(request);
    const body = await readAccountJson(request, activationCompleteSchema);
    await enforceAccountRateLimit({
      scope: "account-activation-complete",
      identifier: session.accountId,
      limit: 10,
      windowSeconds: 10 * 60,
    });
    await authorizeAccountActivation(session.accountId, body.code);
    await writeAuditEvent({
      eventType: "account.activation.authorize",
      outcome: "success",
      accountId: session.accountId,
    });
    return accountJson({ ok: true, state: "authorized" });
  } catch (error) {
    return accountErrorResponse(error, "activation_unavailable");
  }
}
