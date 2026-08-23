import type { NextRequest } from "next/server";

import { enforceAccountRateLimit, finishAccountAuthentication } from "@/lib/server/account/auth";
import {
  accountErrorResponse,
  assertAccountAuthRequest,
  readAccountJson,
} from "@/lib/server/account/http";
import { normalizeLevikId } from "@/lib/server/account/identifiers";
import { authenticateRecoveryCode } from "@/lib/server/account/recovery";
import { recoveryAuthSchema } from "@/lib/server/account/schemas";
import { clientAddressFromHeaders } from "@/lib/server/security";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    assertAccountAuthRequest(request);
    const body = await readAccountJson(request, recoveryAuthSchema);
    const levikId = normalizeLevikId(body.levikId);
    await enforceAccountRateLimit({
      scope: "account-recovery-auth-ip",
      identifier: clientAddressFromHeaders(request.headers),
      limit: 10,
      windowSeconds: 30 * 60,
    });
    await enforceAccountRateLimit({
      scope: "account-recovery-auth-id",
      identifier: levikId.toLowerCase(),
      limit: 5,
      windowSeconds: 30 * 60,
    });
    const account = await authenticateRecoveryCode(levikId, body.code);
    return finishAccountAuthentication({
      request,
      account,
      authMethod: "recovery",
      deviceName: body.deviceName,
    });
  } catch (error) {
    return accountErrorResponse(error, "recovery_auth_unavailable");
  }
}
