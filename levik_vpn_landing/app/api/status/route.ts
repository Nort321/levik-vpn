import { NextResponse } from "next/server";

import { getStatusSnapshot } from "@/lib/server/remnawave-status";

export const dynamic = "force-dynamic";

const HEADERS = {
  "Cache-Control": "public, no-store, max-age=0",
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff",
};

export async function GET() {
  try {
    return NextResponse.json(await getStatusSnapshot(), { headers: HEADERS });
  } catch {
    return NextResponse.json(
      { ok: false, message: "Статус временно недоступен." },
      { status: 503, headers: HEADERS },
    );
  }
}
