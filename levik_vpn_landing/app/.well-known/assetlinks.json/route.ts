import { buildAndroidAssetLinks } from "@/lib/server/android-app-links";
import { getEnvironment } from "@/lib/server/env";

export const dynamic = "force-dynamic";

const PUBLIC_HEADERS = {
  "Cache-Control": "public, max-age=3600, stale-while-revalidate=86400",
  "Content-Type": "application/json; charset=utf-8",
  "X-Content-Type-Options": "nosniff",
};

export function GET() {
  const environment = getEnvironment();
  const statements = buildAndroidAssetLinks(
    environment.MOBILE_ANDROID_PACKAGE_NAME,
    environment.mobileAndroidCertificateDigests,
  );
  if (statements.length === 0) {
    return Response.json([], {
      status: 503,
      headers: {
        ...PUBLIC_HEADERS,
        "Cache-Control": "public, max-age=60, must-revalidate",
      },
    });
  }
  return Response.json(statements, { headers: PUBLIC_HEADERS });
}
