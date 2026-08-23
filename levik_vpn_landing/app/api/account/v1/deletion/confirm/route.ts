import type { NextRequest } from "next/server";

import { confirmAccountDeletion } from "@/lib/server/account/deletion";
import { AccountApiError } from "@/lib/server/account/errors";
import {
  accountErrorResponse,
  accountJson,
  authenticateAccountMutation,
  readAccountJson,
} from "@/lib/server/account/http";
import { deletionConfirmSchema } from "@/lib/server/account/schemas";
import {
  clearAccountSessionCookie,
  isRecentlyAuthenticated,
} from "@/lib/server/account/session";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    const session = await authenticateAccountMutation(request);
    const body = await readAccountJson(request, deletionConfirmSchema);
    if (!isRecentlyAuthenticated(session)) {
      throw new AccountApiError("reauthentication_required", 403);
    }
    await confirmAccountDeletion(session.accountId, body.confirmationToken);
    const response = accountJson({ ok: true });
    clearAccountSessionCookie(response);
    return response;
  } catch (error) {
    return accountErrorResponse(error, "deletion_unavailable");
  }
}
