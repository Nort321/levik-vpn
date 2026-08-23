import type { NextRequest } from "next/server";

import { writeAuditEvent } from "@/lib/server/audit";
import { AccountApiError } from "@/lib/server/account/errors";
import {
  accountErrorResponse,
  accountJson,
  authenticateAccountMutation,
} from "@/lib/server/account/http";
import { revokeAccountDevice } from "@/lib/server/account/legacy";

export const dynamic = "force-dynamic";

export async function DELETE(
  request: NextRequest,
  context: { params: Promise<{ deviceId: string }> },
) {
  try {
    const session = await authenticateAccountMutation(request);
    const deviceId = (await context.params).deviceId;
    if (!/^[0-9a-f-]{36}$/i.test(deviceId)) {
      throw new AccountApiError("device_not_found", 404);
    }
    if (!(await revokeAccountDevice(session.accountId, deviceId))) {
      throw new AccountApiError("device_not_found", 404);
    }
    await writeAuditEvent({
      eventType: "account.device.revoke",
      outcome: "success",
      accountId: session.accountId,
    });
    return accountJson({ ok: true });
  } catch (error) {
    return accountErrorResponse(error, "device_revoke_unavailable");
  }
}
