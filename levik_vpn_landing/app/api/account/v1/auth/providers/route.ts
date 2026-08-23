import type { NextRequest } from "next/server";

import { enforceAccountRateLimit } from "@/lib/server/account/auth";
import { createGoogleChallenge } from "@/lib/server/account/google";
import { accountErrorResponse, accountJson } from "@/lib/server/account/http";
import {
  ACCOUNT_AUTH_CHALLENGE_COOKIE_NAME,
  accountAuthChallengeCookieOptions,
} from "@/lib/server/account/session";
import { WEBAUTHN_RP_ID } from "@/lib/server/account/passkey";
import { getEnvironment } from "@/lib/server/env";
import { clientAddressFromHeaders } from "@/lib/server/security";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    await enforceAccountRateLimit({
      scope: "account-providers-ip",
      identifier: clientAddressFromHeaders(request.headers),
      limit: 30,
      windowSeconds: 5 * 60,
    });
    const environment = getEnvironment();
    const nonce = await createGoogleChallenge();
    const response = accountJson({
      ok: true,
      providers: {
        google: {
          enabled: nonce !== null,
          clientId: environment.GOOGLE_WEB_CLIENT_ID ?? null,
          nonce,
        },
        passkey: { enabled: true, rpId: WEBAUTHN_RP_ID },
        password: { enabled: true },
        recovery: { enabled: true },
        telegram: { enabled: true },
      },
    });
    if (nonce) {
      response.cookies.set(
        ACCOUNT_AUTH_CHALLENGE_COOKIE_NAME,
        nonce,
        accountAuthChallengeCookieOptions,
      );
    }
    return response;
  } catch (error) {
    return accountErrorResponse(error);
  }
}
