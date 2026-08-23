import type { NextRequest } from "next/server";

import { AccountApiError } from "@/lib/server/account/errors";
import {
  accountErrorResponse,
  accountJson,
  authenticateAccountMutation,
  readAccountJson,
} from "@/lib/server/account/http";
import { createPasskeyRegistrationOptions } from "@/lib/server/account/passkey";
import { passkeyRegistrationOptionsSchema } from "@/lib/server/account/schemas";
import { isRecentlyAuthenticated } from "@/lib/server/account/session";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    const session = await authenticateAccountMutation(request);
    if (!isRecentlyAuthenticated(session)) {
      throw new AccountApiError("reauthentication_required", 403);
    }
    await readAccountJson(request, passkeyRegistrationOptionsSchema);
    const ceremony = await createPasskeyRegistrationOptions(session.accountId);
    return accountJson({ ok: true, ...ceremony });
  } catch (error) {
    return accountErrorResponse(error, "passkey_registration_unavailable");
  }
}
