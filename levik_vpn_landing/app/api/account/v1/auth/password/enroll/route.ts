import type { NextRequest } from "next/server";

import { enforceAccountRateLimit } from "@/lib/server/account/auth";
import { enrollPasswordAccount } from "@/lib/server/account/enrollment";
import {
  accountErrorResponse,
  accountJson,
  assertAccountAuthRequest,
  readAccountJson,
} from "@/lib/server/account/http";
import { publicAccount } from "@/lib/server/account/model";
import { passwordEnrollmentSchema } from "@/lib/server/account/schemas";
import {
  csrfForAccountSession,
  setAccountSessionCookie,
} from "@/lib/server/account/session";
import { clientAddressFromHeaders } from "@/lib/server/security";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    assertAccountAuthRequest(request);
    const body = await readAccountJson(request, passwordEnrollmentSchema);
    const clientAddress = clientAddressFromHeaders(request.headers);
    await enforceAccountRateLimit({
      scope: "account-password-enroll-ip",
      identifier: clientAddress,
      limit: 3,
      windowSeconds: 60 * 60,
    });
    const enrollment = await enrollPasswordAccount({
      displayName: body.displayName,
      password: body.password,
      sessionContext: {
        deviceName: body.deviceName,
        userAgent: request.headers.get("user-agent"),
        clientAddress,
      },
    });
    const response = accountJson(
      {
        ok: true,
        account: publicAccount(enrollment.account),
        session: {
          id: enrollment.session.publicId,
          expiresAt: enrollment.session.absoluteExpiresAt.toISOString(),
        },
        csrfToken: csrfForAccountSession(enrollment.session),
        recoveryCodes: enrollment.recoveryCodes,
      },
      { status: 201 },
    );
    setAccountSessionCookie(response, enrollment.session.rawToken);
    return response;
  } catch (error) {
    return accountErrorResponse(error, "password_enrollment_unavailable");
  }
}
