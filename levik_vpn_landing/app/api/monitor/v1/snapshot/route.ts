import { getMonitorOverview } from "@/lib/server/monitor";

export const runtime = "nodejs";

export async function GET() {
  try {
    const snapshot = await getMonitorOverview();
    return Response.json(snapshot, {
      headers: {
        "Cache-Control": "public, max-age=15, stale-while-revalidate=30",
      },
    });
  } catch {
    return Response.json(
      { error: "temporarily_unavailable" },
      { status: 503, headers: { "Cache-Control": "no-store" } },
    );
  }
}
