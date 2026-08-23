import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import { getBridgeSnapshot } from "@/lib/server/bridge/cabinet";
import {
  authenticateFormRequest,
} from "@/lib/server/route-auth";
import { RequestSecurityError } from "@/lib/server/security";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    const { session, values } = await authenticateFormRequest(
      request,
      new Set(["csrf", "subscriptionId"]),
    );
    if (
      !values.subscriptionId ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
        values.subscriptionId,
      )
    ) {
      throw new RequestSecurityError("Resource not found", 404);
    }

    const snapshot = await getBridgeSnapshot(session.grant);
    if (snapshot.user.userKey !== session.userKey) {
      throw new RequestSecurityError("Resource not found", 404);
    }
    const subscription = snapshot.subscriptions.find(
      (item) =>
        item.uuid === values.subscriptionId &&
        item.subscriptionUrl !== null &&
        item.status.toLowerCase() === "active",
    );
    if (!subscription?.subscriptionUrl) {
      throw new RequestSecurityError("Resource not found", 404);
    }

    return new NextResponse(null, {
      status: 303,
      headers: {
        "Cache-Control": "private, no-store",
        Location: subscription.subscriptionUrl,
        "Referrer-Policy": "no-referrer",
        "X-Robots-Tag": "noindex, nofollow",
      },
    });
  } catch (error) {
    const status = error instanceof RequestSecurityError ? error.status : 503;
    return NextResponse.redirect(
      new URL(
        status === 401 ? "/login" : "/dashboard/subscriptions",
        request.url,
      ),
      303,
    );
  }
}
