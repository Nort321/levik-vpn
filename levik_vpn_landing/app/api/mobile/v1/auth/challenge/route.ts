import { NextResponse } from "next/server";

import { createAccountActivation } from "@/lib/server/account/activation";
import { beginDeviceLogin } from "@/lib/server/login-service";
import {
  bindMobileLogin,
  authenticateMobileRegistration,
} from "@/lib/server/mobile-auth";
import { assertMobileAppIntegrity } from "@/lib/server/mobile-integrity";
import {
  mobileChallengeSchema,
  assertMobileRequestTarget,
  MobileApiError,
  MOBILE_NO_STORE_HEADERS,
  mobileRequestProof,
  readMobileJson,
} from "@/lib/server/mobile-api";
import { mobileErrorResponse } from "@/lib/server/mobile-route";
import { consumeRateLimit } from "@/lib/server/rate-limit";
import { clientAddressFromHeaders } from "@/lib/server/security";
import { createLocalAccountLoginAttempt } from "@/lib/server/session-store";

export const dynamic = "force-dynamic";

const ROUTE_PATH = "/api/mobile/v1/auth/challenge";

export async function POST(request: Request) {
  try {
    assertMobileRequestTarget(request, ROUTE_PATH);
    const clientAddress = clientAddressFromHeaders(request.headers);
    const ipLimit = await consumeRateLimit({
      scope: "mobile-auth-start-ip",
      identifier: clientAddress,
      limit: 10,
      windowSeconds: 10 * 60,
    });
    if (!ipLimit.allowed) {
      throw new MobileApiError("rate_limited", 429, true);
    }

    const { body, value } = await readMobileJson(
      request,
      mobileChallengeSchema,
    );
    const proof = mobileRequestProof(request.headers);
    const publicKey = await authenticateMobileRegistration(
      value.publicKeySpki,
      proof,
      request.method,
      ROUTE_PATH,
      body,
      value.requestSigningAlgorithm,
    );
    await assertMobileAppIntegrity({
      headers: request.headers,
      method: request.method,
      path: ROUTE_PATH,
      proof,
      accessToken: "",
      body,
    });
    const deviceLimit = await consumeRateLimit({
      scope: "mobile-auth-start-device",
      identifier: publicKey.deviceId,
      limit: 5,
      windowSeconds: 10 * 60,
    });
    if (!deviceLimit.allowed) {
      throw new MobileApiError("rate_limited", 429, true);
    }

    const attempt = value.accountActivationSupported
      ? await createLocalAccountLoginAttempt()
      : await beginDeviceLogin();
    await bindMobileLogin(
      attempt.browserToken,
      publicKey,
      {
        label: value.deviceLabel,
        appVersion: value.appVersion,
        osVersion: value.deviceOs,
        model: value.deviceModel,
      },
      {
        requestSigning: value.requestSigningAlgorithm,
        profileEncryption: value.profileEncryptionAlgorithm,
      },
    );
    const activation =
      attempt.provider === "account_local"
        ? await createAccountActivation(attempt.browserToken, attempt.expiresAt)
        : null;
    return NextResponse.json(
      {
        ok: true,
        loginToken: attempt.browserToken,
        ...(attempt.provider === "legacy_bridge"
          ? {
              verificationCode: attempt.verificationCode,
              verificationUriComplete: attempt.verificationUriComplete,
            }
          : {}),
        ...(activation
          ? {
              accountActivationSupported: true,
              activationCode: activation.code,
              activationUriComplete: activation.uri,
            }
          : {}),
        pollIntervalSeconds: attempt.pollIntervalSeconds,
        expiresAt: attempt.expiresAt.toISOString(),
      },
      {
        status: 201,
        headers: MOBILE_NO_STORE_HEADERS,
      },
    );
  } catch (error) {
    return mobileErrorResponse(error, "login_unavailable");
  }
}
