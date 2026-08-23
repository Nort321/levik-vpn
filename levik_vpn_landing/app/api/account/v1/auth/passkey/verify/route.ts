import type { NextRequest } from "next/server";

import { enforceAccountRateLimit, finishAccountAuthentication } from "@/lib/server/account/auth";
import {
  accountErrorResponse,
  assertAccountAuthRequest,
  readAccountJson,
} from "@/lib/server/account/http";
import {
  parseAuthenticationResponse,
  verifyPasskeyAuthentication,
} from "@/lib/server/account/passkey";
import { passkeyAuthenticationVerifySchema } from "@/lib/server/account/schemas";
import { clientAddressFromHeaders } from "@/lib/server/security";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    assertAccountAuthRequest(request);
    const body = await readAccountJson(request, passkeyAuthenticationVerifySchema);
    await enforceAccountRateLimit({
      scope: "account-passkey-verify-ip",
      identifier: clientAddressFromHeaders(request.headers),
      limit: 20,
      windowSeconds: 10 * 60,
    });
    const account = await verifyPasskeyAuthentication({
      ceremonyId: body.ceremonyId,
      response: parseAuthenticationResponse(body.response),
    });
    return finishAccountAuthentication({
      request,
      account,
      authMethod: "passkey",
      deviceName: body.deviceName,
    });
  } catch (error) {
    return accountErrorResponse(error, "passkey_auth_unavailable");
  }
}
