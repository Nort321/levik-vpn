import type { NextRequest } from "next/server";

import { writeAuditEvent } from "@/lib/server/audit";
import { AccountApiError } from "@/lib/server/account/errors";
import {
  accountErrorResponse,
  accountJson,
  authenticateAccountMutation,
  readAccountJson,
} from "@/lib/server/account/http";
import {
  parseRegistrationResponse,
  verifyPasskeyRegistration,
} from "@/lib/server/account/passkey";
import { passkeyRegistrationVerifySchema } from "@/lib/server/account/schemas";
import { isRecentlyAuthenticated } from "@/lib/server/account/session";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    const session = await authenticateAccountMutation(request);
    if (!isRecentlyAuthenticated(session)) {
      throw new AccountApiError("reauthentication_required", 403);
    }
    const body = await readAccountJson(request, passkeyRegistrationVerifySchema);
    const passkey = await verifyPasskeyRegistration({
      accountId: session.accountId,
      ceremonyId: body.ceremonyId,
      response: parseRegistrationResponse(body.response),
      name: body.name,
    });
    await writeAuditEvent({
      eventType: "account.passkey.register",
      outcome: "success",
      accountId: session.accountId,
    });
    return accountJson({ ok: true, passkey });
  } catch (error) {
    return accountErrorResponse(error, "passkey_registration_unavailable");
  }
}
