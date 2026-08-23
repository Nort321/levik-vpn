import { NextResponse } from "next/server";

import { writeAuditEvent } from "@/lib/server/audit";
import { revokeBridgeGrant } from "@/lib/server/bridge/auth";
import { BridgeError } from "@/lib/server/bridge/core";
import { authenticateMobileSessionRequest } from "@/lib/server/mobile-auth";
import {
  assertMobileRequestTarget,
  mobileBearerToken,
  MobileApiError,
  mobileLogoutSchema,
  MOBILE_NO_STORE_HEADERS,
  mobileRequestProof,
  readMobileJson,
} from "@/lib/server/mobile-api";
import { mobileErrorResponse } from "@/lib/server/mobile-route";
import { consumeRateLimit } from "@/lib/server/rate-limit";
import {
  markGrantRevocationFailed,
  markGrantRevoked,
  revokeSessions,
} from "@/lib/server/session-store";

export const dynamic = "force-dynamic";

const ROUTE_PATH = "/api/mobile/v1/auth/logout";

export async function POST(request: Request) {
  try {
    assertMobileRequestTarget(request, ROUTE_PATH);
    const { body } = await readMobileJson(request, mobileLogoutSchema);
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
      scope: "mobile-logout-user",
      identifier: mobile.session.userKey,
      limit: 10,
      windowSeconds: 10 * 60,
    });
    if (!limit.allowed) {
      throw new MobileApiError("rate_limited", 429, true);
    }

    const revocations = await revokeSessions(mobile.session, {});
    for (const revocation of revocations) {
      try {
        await revokeBridgeGrant(
          revocation.grant,
          revocation.idempotencyKey,
        );
        await markGrantRevoked(revocation.tokenHash);
      } catch (error) {
        await markGrantRevocationFailed(
          revocation.tokenHash,
          error instanceof BridgeError
            ? error.code
            : "bridge_unavailable",
        ).catch(() => {});
      }
    }
    await writeAuditEvent({
      eventType: "mobile_auth.logout",
      outcome: "success",
      userKey: mobile.session.userKey,
    });
    return NextResponse.json(
      { ok: true },
      { headers: MOBILE_NO_STORE_HEADERS },
    );
  } catch (error) {
    return mobileErrorResponse(error, "logout_unavailable");
  }
}
