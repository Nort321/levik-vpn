import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import {
  getBridgeOrderStatus,
  getBridgeSnapshot,
} from "@/lib/server/bridge/cabinet";
import { authenticateFormRequest } from "@/lib/server/route-auth";
import { RequestSecurityError } from "@/lib/server/security";
import {
  findOrderByPublicId,
} from "@/lib/web/view-models";

export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  try {
    const { session, values } = await authenticateFormRequest(
      request,
      new Set(["csrf", "publicOrderId"]),
    );
    if (
      !values.publicOrderId ||
      !/^ord_[A-Za-z0-9_-]{22}$/.test(values.publicOrderId)
    ) {
      throw new RequestSecurityError("Resource not found", 404);
    }

    const snapshot = await getBridgeSnapshot(session.grant);
    if (snapshot.user.userKey !== session.userKey) {
      throw new RequestSecurityError("Resource not found", 404);
    }
    const snapshotOrder = findOrderByPublicId(
      snapshot,
      session.userKey,
      values.publicOrderId,
    );
    if (!snapshotOrder) {
      throw new RequestSecurityError("Resource not found", 404);
    }

    const order = await getBridgeOrderStatus(
      session.grant,
      snapshotOrder.id,
    );
    const paymentPending = new Set(["pending", "pending_payment"]).has(
      order.status.toLowerCase(),
    );
    if (
      order.id !== snapshotOrder.id ||
      !paymentPending ||
      !order.paymentUrl
    ) {
      throw new RequestSecurityError("Payment is not available", 409);
    }

    return new NextResponse(null, {
      status: 303,
      headers: {
        "Cache-Control": "private, no-store",
        Location: order.paymentUrl,
        "Referrer-Policy": "no-referrer",
        "X-Robots-Tag": "noindex, nofollow",
      },
    });
  } catch (error) {
    const status = error instanceof RequestSecurityError ? error.status : 503;
    return NextResponse.redirect(
      new URL(status === 401 ? "/login" : "/dashboard/orders", request.url),
      303,
    );
  }
}
