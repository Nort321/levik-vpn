import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import { getOptionalSession } from "@/lib/server/browser-auth";
import { getBridgeSnapshot } from "@/lib/server/bridge/cabinet";
import {
  assertSameOriginNavigation,
} from "@/lib/server/route-auth";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    assertSameOriginNavigation(request);
    const session = await getOptionalSession();
    if (!session) {
      return NextResponse.redirect(new URL("/login", request.url), 303);
    }
    const snapshot = await getBridgeSnapshot(session.grant);
    if (snapshot.user.userKey !== session.userKey) {
      throw new Error("identity_mismatch");
    }
    if (!snapshot.referrals) {
      return NextResponse.redirect(new URL("/dashboard", request.url), 303);
    }

    const target = new URL("https://t.me/share/url");
    target.searchParams.set("url", snapshot.referrals.referralLink);
    target.searchParams.set(
      "text",
      "Попробуй Levik VPN — быстрый VPN для обычной и мобильной сети.",
    );
    return new NextResponse(null, {
      status: 303,
      headers: {
        "Cache-Control": "private, no-store",
        Location: target.toString(),
        "Referrer-Policy": "no-referrer",
      },
    });
  } catch {
    return NextResponse.redirect(new URL("/dashboard/referrals", request.url), 303);
  }
}
