import { NextResponse } from "next/server";

import { writeAuditEvent } from "@/lib/server/audit";
import {
  getAuthorizedAccountActivation,
  issueBridgeAuthorizationForAccount,
  markAccountActivationConsumed,
} from "@/lib/server/account/activation";
import {
  ensureLegacyAccount,
  upsertAccountDevice,
} from "@/lib/server/account/legacy";
import { linkLegacyAccountSession } from "@/lib/server/account/session";
import { getDeviceAuthorizationStatus } from "@/lib/server/bridge/auth";
import {
  authenticateMobileLoginRequest,
  promoteMobileLogin,
} from "@/lib/server/mobile-auth";
import {
  assertMobileRequestTarget,
  mobileLoginStatusSchema,
  MobileApiError,
  MOBILE_NO_STORE_HEADERS,
  mobileRequestProof,
  readMobileJson,
} from "@/lib/server/mobile-api";
import { mobileErrorResponse } from "@/lib/server/mobile-route";
import { consumeRateLimit } from "@/lib/server/rate-limit";
import { clientAddressFromHeaders } from "@/lib/server/security";
import {
  claimLoginAttemptForPolling,
  createSessionFromAuthorization,
  getLoginAttempt,
  releaseLoginPoll,
} from "@/lib/server/session-store";

export const dynamic = "force-dynamic";

const ROUTE_PATH = "/api/mobile/v1/auth/status";

export async function POST(request: Request) {
  let loginToken: string | null = null;
  try {
    assertMobileRequestTarget(request, ROUTE_PATH);
    const { body, value } = await readMobileJson(
      request,
      mobileLoginStatusSchema,
    );
    loginToken = value.loginToken;
    const proof = mobileRequestProof(request.headers);
    const clientAddress = clientAddressFromHeaders(request.headers);
    const ipLimit = await consumeRateLimit({
      scope: "mobile-auth-poll-ip",
      identifier: clientAddress,
      limit: 240,
      windowSeconds: 10 * 60,
    });
    if (!ipLimit.allowed) {
      throw new MobileApiError("rate_limited", 429, true);
    }

    const binding = await authenticateMobileLoginRequest(
      loginToken,
      proof,
      request.method,
      ROUTE_PATH,
      body,
    );
    const deviceLimit = await consumeRateLimit({
      scope: "mobile-auth-poll-device",
      identifier: binding.deviceId,
      limit: 240,
      windowSeconds: 10 * 60,
    });
    if (!deviceLimit.allowed) {
      throw new MobileApiError("rate_limited", 429, true);
    }

    const attempt = await claimLoginAttemptForPolling(loginToken);
    if (!attempt) {
      const existingAttempt = await getLoginAttempt(loginToken);
      if (!existingAttempt) {
        throw new MobileApiError("login_expired", 410);
      }
      return NextResponse.json(
        {
          ok: true,
          state: "pending",
          pollIntervalSeconds: existingAttempt.pollIntervalSeconds,
        },
        { headers: MOBILE_NO_STORE_HEADERS },
      );
    }

    const accountActivation = await getAuthorizedAccountActivation(loginToken);
    if (accountActivation) {
      const authorization = await issueBridgeAuthorizationForAccount(
        accountActivation,
      );
      const session = await createSessionFromAuthorization(
        loginToken,
        authorization,
        {
          userAgent: `LevikVPN-Android/${binding.device.appVersion}`,
          clientAddress,
        },
      );
      if (!session) {
        throw new MobileApiError("login_expired", 410);
      }
      await promoteMobileLogin(loginToken, session);
      await linkLegacyAccountSession(accountActivation.account_id, session);
      await upsertAccountDevice({
        accountId: accountActivation.account_id,
        externalDeviceId: binding.deviceId,
        name: binding.device.label,
        platform: "android",
      });
      await markAccountActivationConsumed(accountActivation.activation_id);
      await writeAuditEvent({
        eventType: "mobile_auth.account_login",
        outcome: "success",
        accountId: accountActivation.account_id,
        userKey: session.userKey,
      });
      return NextResponse.json(
        {
          ok: true,
          state: "authenticated",
          accessToken: session.rawToken,
          expiresAt: session.absoluteExpiresAt.toISOString(),
        },
        { headers: MOBILE_NO_STORE_HEADERS },
      );
    }

    if (attempt.provider === "account_local") {
      await releaseLoginPoll(loginToken);
      return NextResponse.json(
        {
          ok: true,
          state: "pending",
          pollIntervalSeconds: attempt.pollIntervalSeconds,
        },
        { headers: MOBILE_NO_STORE_HEADERS },
      );
    }

    const authorization = await getDeviceAuthorizationStatus(
      attempt.deviceCode,
    );
    if (authorization.status === "authorization_pending") {
      await releaseLoginPoll(loginToken);
      return NextResponse.json(
        {
          ok: true,
          state: "pending",
          pollIntervalSeconds: authorization.interval,
        },
        { headers: MOBILE_NO_STORE_HEADERS },
      );
    }

    const session = await createSessionFromAuthorization(
      loginToken,
      authorization,
      {
        userAgent: `LevikVPN-Android/${binding.device.appVersion}`,
        clientAddress,
      },
    );
    if (!session) {
      throw new MobileApiError("login_expired", 410);
    }
    await promoteMobileLogin(loginToken, session);
    const accountId = await ensureLegacyAccount(session).catch(() => null);
    if (accountId) {
      await upsertAccountDevice({
        accountId,
        externalDeviceId: binding.deviceId,
        name: binding.device.label,
        platform: "android",
      }).catch(() => {});
    }
    await writeAuditEvent({
      eventType: "mobile_auth.login",
      outcome: "success",
      accountId: accountId ?? undefined,
      userKey: session.userKey,
    });
    return NextResponse.json(
      {
        ok: true,
        state: "authenticated",
        accessToken: session.rawToken,
        expiresAt: session.absoluteExpiresAt.toISOString(),
      },
      { headers: MOBILE_NO_STORE_HEADERS },
    );
  } catch (error) {
    if (loginToken) {
      await releaseLoginPoll(loginToken).catch(() => {});
    }
    return mobileErrorResponse(error, "login_unavailable");
  }
}
