import type { NextRequest } from "next/server";

import { writeAuditEvent } from "@/lib/server/audit";
import { AccountApiError } from "@/lib/server/account/errors";
import { linkGoogleIdentity } from "@/lib/server/account/google";
import {
  accountErrorResponse,
  accountJson,
  authenticateAccountMutation,
  readAccountJson,
} from "@/lib/server/account/http";
import { revokeIdentity } from "@/lib/server/account/identity";
import { linkLegacyIdentity } from "@/lib/server/account/legacy";
import { setPasswordCredential } from "@/lib/server/account/password";
import {
  identityDeleteSchema,
  identityMutationSchema,
} from "@/lib/server/account/schemas";
import {
  ACCOUNT_AUTH_CHALLENGE_COOKIE_NAME,
  isRecentlyAuthenticated,
} from "@/lib/server/account/session";
import { SESSION_COOKIE_NAME } from "@/lib/server/browser-auth";
import { constantTimeEqual } from "@/lib/server/crypto";
import { getSessionByToken } from "@/lib/server/session-store";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    const session = await authenticateAccountMutation(request);
    if (!isRecentlyAuthenticated(session)) {
      throw new AccountApiError("reauthentication_required", 403);
    }
    const body = await readAccountJson(request, identityMutationSchema);
    if (body.provider === "google") {
      const cookieNonce =
        request.cookies.get(ACCOUNT_AUTH_CHALLENGE_COOKIE_NAME)?.value ?? "";
      if (!constantTimeEqual(cookieNonce, body.nonce)) {
        throw new AccountApiError("auth_challenge_expired", 409);
      }
      await linkGoogleIdentity(
        session.accountId,
        body.idToken,
        body.nonce,
        body.label,
      );
    } else if (body.provider === "password") {
      await setPasswordCredential(session.accountId, body.password);
    } else {
      const legacyToken = request.cookies.get(SESSION_COOKIE_NAME)?.value;
      const legacySession = legacyToken
        ? await getSessionByToken(legacyToken, false)
        : null;
      if (!legacySession) {
        throw new AccountApiError("telegram_reauthentication_required", 401);
      }
      await linkLegacyIdentity(session.accountId, session, legacySession);
    }
    await writeAuditEvent({
      eventType: "account.identity.link",
      outcome: "success",
      accountId: session.accountId,
      metadata: { identityProvider: body.provider },
    });
    return accountJson({ ok: true });
  } catch (error) {
    return accountErrorResponse(error, "identity_link_unavailable");
  }
}

export async function DELETE(request: NextRequest) {
  try {
    const session = await authenticateAccountMutation(request);
    if (!isRecentlyAuthenticated(session)) {
      throw new AccountApiError("reauthentication_required", 403);
    }
    const body = await readAccountJson(request, identityDeleteSchema);
    await revokeIdentity(session.accountId, body.identityId);
    await writeAuditEvent({
      eventType: "account.identity.revoke",
      outcome: "success",
      accountId: session.accountId,
    });
    return accountJson({ ok: true });
  } catch (error) {
    return accountErrorResponse(error, "identity_revoke_unavailable");
  }
}
