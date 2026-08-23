import { NextResponse } from "next/server";

import { getBridgeSnapshot } from "@/lib/server/bridge/cabinet";
import { mobileAccountSnapshot } from "@/lib/server/mobile-account";
import { authenticateMobileSessionRequest } from "@/lib/server/mobile-auth";
import {
  assertMobileRequestTarget,
  emptyMobileRequestBody,
  mobileBearerToken,
  MobileApiError,
  MOBILE_NO_STORE_HEADERS,
  mobileRequestProof,
} from "@/lib/server/mobile-api";
import { mobileErrorResponse } from "@/lib/server/mobile-route";
import { consumeRateLimit } from "@/lib/server/rate-limit";

export const dynamic = "force-dynamic";

const ROUTE_PATH = "/api/mobile/v1/account";

export async function GET(request: Request) {
  try {
    assertMobileRequestTarget(request, ROUTE_PATH);
    const body = emptyMobileRequestBody(request);
    const proof = mobileRequestProof(request.headers);
    const accessToken = mobileBearerToken(request.headers);
    const mobile = await authenticateMobileSessionRequest(
      accessToken,
      proof,
      request.method,
      ROUTE_PATH,
      body,
    );
    const limit = await consumeRateLimit({
      scope: "mobile-account-user",
      identifier: mobile.session.userKey,
      limit: 120,
      windowSeconds: 60,
    });
    if (!limit.allowed) {
      throw new MobileApiError("rate_limited", 429, true);
    }

    const snapshot = await getBridgeSnapshot(mobile.session.grant);
    if (snapshot.user.userKey !== mobile.session.userKey) {
      throw new MobileApiError("account_not_found", 404);
    }
    return NextResponse.json(
      {
        ok: true,
        ...mobileAccountSnapshot(snapshot),
      },
      { headers: MOBILE_NO_STORE_HEADERS },
    );
  } catch (error) {
    return mobileErrorResponse(error, "account_unavailable");
  }
}
