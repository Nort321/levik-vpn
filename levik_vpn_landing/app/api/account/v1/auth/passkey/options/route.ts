import type { NextRequest } from "next/server";

import { enforceAccountRateLimit } from "@/lib/server/account/auth";
import {
  accountErrorResponse,
  accountJson,
  assertAccountAuthRequest,
  readAccountJson,
} from "@/lib/server/account/http";
import { createPasskeyAuthenticationOptions } from "@/lib/server/account/passkey";
import { passkeyAuthenticationOptionsSchema } from "@/lib/server/account/schemas";
import { clientAddressFromHeaders } from "@/lib/server/security";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    assertAccountAuthRequest(request);
    const body = await readAccountJson(request, passkeyAuthenticationOptionsSchema);
    await enforceAccountRateLimit({
      scope: "account-passkey-options-ip",
      identifier: clientAddressFromHeaders(request.headers),
      limit: 30,
      windowSeconds: 10 * 60,
    });
    const ceremony = await createPasskeyAuthenticationOptions(body.levikId);
    return accountJson({ ok: true, ...ceremony });
  } catch (error) {
    return accountErrorResponse(error, "passkey_auth_unavailable");
  }
}
