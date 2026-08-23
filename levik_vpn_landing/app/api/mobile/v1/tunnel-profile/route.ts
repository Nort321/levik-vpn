import { NextResponse } from "next/server";

import { getBridgeSnapshot } from "@/lib/server/bridge/cabinet";
import { authenticateMobileSessionRequest } from "@/lib/server/mobile-auth";
import {
  assertMobileRequestTarget,
  mobileBearerToken,
  MobileApiError,
  MOBILE_NO_STORE_HEADERS,
  mobileRequestProof,
  mobileTunnelProfileSchema,
  readMobileJson,
} from "@/lib/server/mobile-api";
import { assertMobileAppIntegrity } from "@/lib/server/mobile-integrity";
import { buildEncryptedMobileProfile } from "@/lib/server/mobile-profile";
import { mobileErrorResponse } from "@/lib/server/mobile-route";
import { consumeRateLimit } from "@/lib/server/rate-limit";

export const dynamic = "force-dynamic";

const ROUTE_PATH = "/api/mobile/v1/tunnel-profile";

export async function POST(request: Request) {
  try {
    assertMobileRequestTarget(request, ROUTE_PATH);
    const { body, value } = await readMobileJson(
      request,
      mobileTunnelProfileSchema,
    );
    const proof = mobileRequestProof(request.headers);
    const accessToken = mobileBearerToken(request.headers);
    const mobile = await authenticateMobileSessionRequest(
      accessToken,
      proof,
      request.method,
      ROUTE_PATH,
      body,
    );
    await assertMobileAppIntegrity({
      headers: request.headers,
      method: request.method,
      path: ROUTE_PATH,
      proof,
      accessToken,
      body,
    });
    const limit = await consumeRateLimit({
      scope: "mobile-profile-user",
      identifier: mobile.session.userKey,
      limit: 30,
      windowSeconds: 10 * 60,
    });
    if (!limit.allowed) {
      throw new MobileApiError("rate_limited", 429, true);
    }

    const snapshot = await getBridgeSnapshot(mobile.session.grant);
    if (snapshot.user.userKey !== mobile.session.userKey) {
      throw new MobileApiError("subscription_not_found", 404);
    }
    const now = Date.now();
    const subscription = snapshot.subscriptions.find(
      (candidate) =>
        candidate.uuid === value.subscriptionId &&
        candidate.status.toLowerCase() === "active" &&
        candidate.subscriptionUrl !== null &&
        (!candidate.expireAt ||
          new Date(candidate.expireAt).getTime() > now),
    );
    if (!subscription) {
      throw new MobileApiError("subscription_not_found", 404);
    }

    const profile = await buildEncryptedMobileProfile({
      subscription,
      sessionPublicId: mobile.session.publicId,
      deviceId: mobile.deviceId,
      device: mobile.device,
      publicKey: mobile.publicKey,
      profileEncryptionAlgorithm: mobile.profileEncryptionAlgorithm,
    });
    return NextResponse.json(
      {
        ok: true,
        profile,
      },
      { headers: MOBILE_NO_STORE_HEADERS },
    );
  } catch (error) {
    return mobileErrorResponse(error, "profile_unavailable");
  }
}
