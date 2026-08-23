import { NextResponse } from "next/server";
import { requireSession } from "@/lib/server/browser-auth";
import { deleteAppUpdate, isAdminUser, setActiveAppUpdate } from "@/lib/server/app-updates";

export const dynamic = "force-dynamic";

export async function POST(request: Request) {
  try {
    const session = await requireSession();
    if (!isAdminUser(session.userKey)) {
      return NextResponse.json({ ok: false, error: "Access denied" }, { status: 403 });
    }

    const body: unknown = await request.json();
    const action = body && typeof body === "object" && "action" in body
      ? body.action
      : null;
    const id = body && typeof body === "object" && "id" in body
      ? body.id
      : null;

    if (!id || typeof id !== "string") {
      return NextResponse.json({ ok: false, error: "Release ID is required" }, { status: 400 });
    }

    if (action === "set_active") {
      const success = await setActiveAppUpdate(id);
      return NextResponse.json({ ok: success });
    }

    if (action === "delete") {
      const success = await deleteAppUpdate(id);
      return NextResponse.json({ ok: success });
    }

    return NextResponse.json({ ok: false, error: "Invalid action" }, { status: 400 });
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : "Internal Server Error";
    return NextResponse.json({ ok: false, error: msg }, { status: 500 });
  }
}
