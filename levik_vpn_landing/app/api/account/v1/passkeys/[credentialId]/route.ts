import type { NextRequest } from "next/server";

import { writeAuditEvent } from "@/lib/server/audit";
import { AccountApiError } from "@/lib/server/account/errors";
import {
  accountErrorResponse,
  accountJson,
  authenticateAccountMutation,
  readAccountJson,
} from "@/lib/server/account/http";
import { renamePasskey, revokePasskey } from "@/lib/server/account/passkey";
import { passkeyRenameSchema } from "@/lib/server/account/schemas";
import { isRecentlyAuthenticated } from "@/lib/server/account/session";

export const dynamic = "force-dynamic";

function credentialId(value: string): string {
  if (!/^[A-Za-z0-9_-]{1,1024}$/.test(value)) {
    throw new AccountApiError("passkey_not_found", 404);
  }
  return value;
}

export async function PATCH(
  request: NextRequest,
  context: { params: Promise<{ credentialId: string }> },
) {
  try {
    const session = await authenticateAccountMutation(request);
    const body = await readAccountJson(request, passkeyRenameSchema);
    await renamePasskey(
      session.accountId,
      credentialId((await context.params).credentialId),
      body.name,
    );
    return accountJson({ ok: true });
  } catch (error) {
    return accountErrorResponse(error, "passkey_update_unavailable");
  }
}

export async function DELETE(
  request: NextRequest,
  context: { params: Promise<{ credentialId: string }> },
) {
  try {
    const session = await authenticateAccountMutation(request);
    if (!isRecentlyAuthenticated(session)) {
      throw new AccountApiError("reauthentication_required", 403);
    }
    await revokePasskey(
      session.accountId,
      credentialId((await context.params).credentialId),
    );
    await writeAuditEvent({
      eventType: "account.passkey.revoke",
      outcome: "success",
      accountId: session.accountId,
    });
    return accountJson({ ok: true });
  } catch (error) {
    return accountErrorResponse(error, "passkey_revoke_unavailable");
  }
}
