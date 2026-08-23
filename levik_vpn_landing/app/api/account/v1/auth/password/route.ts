import type { NextRequest } from "next/server";

import { enforceAccountRateLimit, finishAccountAuthentication } from "@/lib/server/account/auth";
import {
  accountErrorResponse,
  assertAccountAuthRequest,
  readAccountJson,
} from "@/lib/server/account/http";
import { normalizeLevikId } from "@/lib/server/account/identifiers";
import { authenticatePassword } from "@/lib/server/account/password";
import { passwordAuthSchema } from "@/lib/server/account/schemas";
import { clientAddressFromHeaders } from "@/lib/server/security";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    assertAccountAuthRequest(request);
    const body = await readAccountJson(request, passwordAuthSchema);
    const levikId = normalizeLevikId(body.levikId);
    const clientAddress = clientAddressFromHeaders(request.headers);
    await enforceAccountRateLimit({
      scope: "account-password-auth-ip",
      identifier: clientAddress,
      limit: 10,
      windowSeconds: 10 * 60,
    });
    await enforceAccountRateLimit({
      scope: "account-password-auth-id",
      identifier: levikId.toLowerCase(),
      limit: 5,
      windowSeconds: 10 * 60,
    });
    const account = await authenticatePassword(levikId, body.password);
    return finishAccountAuthentication({
      request,
      account,
      authMethod: "password",
      deviceName: body.deviceName,
    });
  } catch (error) {
    return accountErrorResponse(error, "password_auth_unavailable");
  }
}
