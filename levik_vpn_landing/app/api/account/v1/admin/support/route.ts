import type { NextRequest } from "next/server";

import { enforceAccountRateLimit } from "@/lib/server/account/auth";
import { AccountApiError } from "@/lib/server/account/errors";
import { accountErrorResponse, accountJson } from "@/lib/server/account/http";
import {
  csrfForSupportAdmin,
  requireSupportAdmin,
} from "@/lib/server/account/support-admin";
import {
  listSupportTicketsForStaff,
  type SupportStatus,
} from "@/lib/server/account/support";

export const dynamic = "force-dynamic";

const SUPPORT_STATUSES = new Set<SupportStatus>([
  "open",
  "waiting_for_support",
  "waiting_for_user",
  "closed",
]);

export async function GET(request: NextRequest) {
  try {
    const session = await requireSupportAdmin(request);
    const url = new URL(request.url);
    if (
      [...url.searchParams.keys()].some((key) => key !== "status") ||
      url.searchParams.getAll("status").length > 1
    ) {
      throw new AccountApiError("invalid_request", 400);
    }
    const rawStatus = url.searchParams.get("status");
    if (rawStatus !== null && !SUPPORT_STATUSES.has(rawStatus as SupportStatus)) {
      throw new AccountApiError("invalid_request", 400);
    }
    await enforceAccountRateLimit({
      scope: "account-support-admin-read",
      identifier: session.userKey,
      limit: 120,
      windowSeconds: 60,
    });
    return accountJson({
      ok: true,
      tickets: await listSupportTicketsForStaff(rawStatus as SupportStatus | null),
      csrfToken: csrfForSupportAdmin(session),
    });
  } catch (error) {
    return accountErrorResponse(error, "support_admin_unavailable");
  }
}
