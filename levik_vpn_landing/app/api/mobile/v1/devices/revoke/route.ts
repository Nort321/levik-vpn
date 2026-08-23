import { randomUUID } from "node:crypto";
import { NextResponse } from "next/server";

import { writeAuditEvent } from "@/lib/server/audit";
import { getBridgeSnapshot, revokeBridgeDevice } from "@/lib/server/bridge/cabinet";
import { authenticateMobileSessionRequest } from "@/lib/server/mobile-auth";
import {
  assertMobileRequestTarget,
  mobileBearerToken,
  MobileApiError,
  mobileRevokeDeviceSchema,
  MOBILE_NO_STORE_HEADERS,
  mobileRequestProof,
  readMobileJson,
} from "@/lib/server/mobile-api";
import { mobileErrorResponse } from "@/lib/server/mobile-route";
import { consumeRateLimit } from "@/lib/server/rate-limit";

export const dynamic = "force-dynamic";

const ROUTE_PATH = "/api/mobile/v1/devices/revoke";

export async function POST(request: Request) {
  try {
    assertMobileRequestTarget(request, ROUTE_PATH);
    const { body, value } = await readMobileJson(
      request,
      mobileRevokeDeviceSchema,
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
    const limit = await consumeRateLimit({
      scope: "mobile-revoke-device",
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
    const subscription = snapshot.subscriptions.find(
      (sub) => sub.uuid === value.subscriptionId,
    );
    if (!subscription) {
      throw new MobileApiError("subscription_not_found", 404);
    }

    const idempotencyKey = randomUUID();
    await revokeBridgeDevice(
      mobile.session.grant,
      {
        subscriptionUuid: value.subscriptionId,
        deviceId: value.deviceId,
      },
      idempotencyKey,
    );

    await writeAuditEvent({
      eventType: "mobile_devices.revoke",
      outcome: "success",
      userKey: mobile.session.userKey,
    });

    return NextResponse.json(
      { ok: true },
      { headers: MOBILE_NO_STORE_HEADERS },
    );
  } catch (error) {
    return mobileErrorResponse(error, "device_revoke_unavailable");
  }
}
