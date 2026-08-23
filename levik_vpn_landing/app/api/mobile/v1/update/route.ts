import { NextResponse } from "next/server";
import { getActiveAppUpdate } from "@/lib/server/app-updates";

export const dynamic = "force-dynamic";

const NO_STORE_HEADERS = {
  "Cache-Control": "private, no-store, max-age=0",
  "Content-Type": "application/json; charset=utf-8",
};

export async function GET() {
  try {
    const active = await getActiveAppUpdate();
    if (active) {
      return NextResponse.json(
        {
          ok: true,
          update: {
            latestVersionCode: active.versionCode,
            latestVersionName: active.versionName,
            minSupportedVersionCode: active.minSupportedVersionCode,
            downloadUrl: active.downloadUrl,
            sha256: active.sha256,
            titleRu: active.titleRu,
            titleEn: active.titleEn,
            changelogRu: active.changelogRu,
            changelogEn: active.changelogEn,
            forceUpdate: active.forceUpdate,
            publishedAt: active.createdAt.toISOString(),
          },
        },
        {
          headers: {
            "Cache-Control": "public, s-maxage=60, stale-while-revalidate=120",
            "Content-Type": "application/json",
          },
        },
      );
    }
    return NextResponse.json(
      { ok: true, update: null },
      { headers: NO_STORE_HEADERS },
    );
  } catch (err) {
    console.error("Failed to read active app update from DB", err);
    return NextResponse.json(
      { ok: false, code: "temporarily_unavailable" },
      {
        status: 503,
        headers: {
          ...NO_STORE_HEADERS,
          "Retry-After": "60",
        },
      },
    );
  }
}
