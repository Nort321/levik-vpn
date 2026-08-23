import type { NextRequest } from "next/server";

import { enforceAccountRateLimit } from "@/lib/server/account/auth";
import { getAccountActivation } from "@/lib/server/account/activation";
import { AccountApiError } from "@/lib/server/account/errors";
import { accountErrorResponse, accountJson } from "@/lib/server/account/http";
import { clientAddressFromHeaders } from "@/lib/server/security";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    const url = new URL(request.url);
    if ([...url.searchParams.keys()].some((key) => key !== "code")) {
      throw new AccountApiError("invalid_request", 400);
    }
    await enforceAccountRateLimit({
      scope: "account-activation-view-ip",
      identifier: clientAddressFromHeaders(request.headers),
      limit: 30,
      windowSeconds: 10 * 60,
    });
    const activation = await getAccountActivation(url.searchParams.get("code") ?? "");
    return accountJson({ ok: true, activation });
  } catch (error) {
    return accountErrorResponse(error, "activation_unavailable");
  }
}
