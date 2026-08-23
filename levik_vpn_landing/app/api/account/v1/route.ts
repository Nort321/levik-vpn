import type { NextRequest } from "next/server";

import { accountErrorResponse, accountJson } from "@/lib/server/account/http";
import { getAccountOverview } from "@/lib/server/account/overview";
import { requireAccountSession } from "@/lib/server/account/session";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    const session = await requireAccountSession(request);
    return accountJson({ ok: true, ...(await getAccountOverview(session)) });
  } catch (error) {
    return accountErrorResponse(error);
  }
}
