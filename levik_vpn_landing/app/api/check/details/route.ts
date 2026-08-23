import { NextResponse } from "next/server";

import { getIpCheckSnapshot } from "@/lib/server/ip-check";
import {
  clientAddressFromHeaders,
  RequestSecurityError,
} from "@/lib/server/security";

export const dynamic = "force-dynamic";

const HEADERS = {
  "Cache-Control": "private, no-store, max-age=0",
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff",
};

export async function GET(request: Request) {
  try {
    const address = clientAddressFromHeaders(request.headers);
    return NextResponse.json(await getIpCheckSnapshot(address), {
      headers: HEADERS,
    });
  } catch (error) {
    const status = error instanceof RequestSecurityError ? error.status : 503;
    return NextResponse.json(
      {
        ok: false,
        message:
          status === 400
            ? "Не удалось определить IP-адрес."
            : "Расширенная проверка временно недоступна.",
      },
      { status, headers: HEADERS },
    );
  }
}
