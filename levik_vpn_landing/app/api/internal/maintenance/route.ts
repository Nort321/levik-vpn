import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import { getEnvironment } from "@/lib/server/env";
import {
  isMaintenanceRequestAuthorized,
  runMaintenance,
} from "@/lib/server/maintenance";

export const dynamic = "force-dynamic";

const noStoreHeaders = {
  "Cache-Control": "no-store",
} as const;

export async function POST(request: NextRequest) {
  const environment = getEnvironment();
  if (
    !isMaintenanceRequestAuthorized(
      request.headers.get("authorization"),
      environment.MAINTENANCE_TOKEN,
    )
  ) {
    return NextResponse.json(
      { ok: false },
      { status: 404, headers: noStoreHeaders },
    );
  }

  const contentLength = request.headers.get("content-length");
  if (
    request.headers.has("transfer-encoding") ||
    (contentLength !== null && contentLength !== "0")
  ) {
    return NextResponse.json(
      { ok: false },
      { status: 400, headers: noStoreHeaders },
    );
  }

  try {
    const summary = await runMaintenance();
    return NextResponse.json(
      { ok: true, ...summary },
      { headers: noStoreHeaders },
    );
  } catch (error) {
    console.error("Maintenance cycle failed", {
      errorName: error instanceof Error ? error.name : "UnknownError",
    });
    return NextResponse.json(
      { ok: false },
      { status: 503, headers: noStoreHeaders },
    );
  }
}
