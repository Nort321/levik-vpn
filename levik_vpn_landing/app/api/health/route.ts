import { NextResponse } from "next/server";

import { query } from "@/lib/server/db";

export const dynamic = "force-dynamic";

export async function GET() {
  try {
    // Read through the runtime role so health also proves that the account
    // schema migration and its least-privilege grants are present.
    await Promise.all([
      query("SELECT 1 FROM accounts LIMIT 0"),
      query("SELECT 1 FROM account_bridge_principals LIMIT 0"),
      query("SELECT 1 FROM account_bridge_authorizations LIMIT 0"),
      query("SELECT 1 FROM account_legacy_link_reservations LIMIT 0"),
    ]);
    return NextResponse.json(
      { ok: true },
      {
        headers: {
          "Cache-Control": "no-store",
        },
      },
    );
  } catch {
    return NextResponse.json(
      { ok: false },
      {
        status: 503,
        headers: {
          "Cache-Control": "no-store",
        },
      },
    );
  }
}
