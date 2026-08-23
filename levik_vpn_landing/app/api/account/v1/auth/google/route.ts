import type { NextRequest } from "next/server";

import { enforceAccountRateLimit, finishAccountAuthentication } from "@/lib/server/account/auth";
import { AccountApiError } from "@/lib/server/account/errors";
import { authenticateGoogle } from "@/lib/server/account/google";
import {
  accountErrorResponse,
  assertAccountAuthRequest,
  readAccountJson,
} from "@/lib/server/account/http";
import { googleAuthSchema } from "@/lib/server/account/schemas";
import { ACCOUNT_AUTH_CHALLENGE_COOKIE_NAME } from "@/lib/server/account/session";
import { constantTimeEqual } from "@/lib/server/crypto";
import { clientAddressFromHeaders } from "@/lib/server/security";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    assertAccountAuthRequest(request);
    const body = await readAccountJson(request, googleAuthSchema);
    const cookieNonce = request.cookies.get(ACCOUNT_AUTH_CHALLENGE_COOKIE_NAME)?.value ?? "";
    if (!constantTimeEqual(cookieNonce, body.nonce)) {
      throw new AccountApiError("auth_challenge_expired", 409);
    }
    await enforceAccountRateLimit({
      scope: "account-google-auth-ip",
      identifier: clientAddressFromHeaders(request.headers),
      limit: 10,
      windowSeconds: 10 * 60,
    });
    const account = await authenticateGoogle(body.idToken, body.nonce);
    return finishAccountAuthentication({
      request,
      account,
      authMethod: "google",
      deviceName: body.deviceName,
    });
  } catch (error) {
    return accountErrorResponse(error, "google_auth_unavailable");
  }
}
