import { NextResponse } from "next/server";
import { z } from "zod";

import { NOTE_ID_PATTERN } from "@/lib/notes/crypto";
import { assertNoteRequestHeaders } from "@/lib/server/note-request";
import { consumeEncryptedNote } from "@/lib/server/notes";
import { consumeRateLimit } from "@/lib/server/rate-limit";
import { clientAddressFromHeaders, RequestSecurityError } from "@/lib/server/security";

export const dynamic = "force-dynamic";

const NO_STORE_HEADERS = {
  "Cache-Control": "private, no-store, max-age=0",
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff",
} as const;
const consumeSchema = z.object({
  keyCommitment: z.string().regex(/^[A-Za-z0-9_-]{43}$/),
}).strict();

export async function POST(
  request: Request,
  context: { params: Promise<{ id: string }> },
) {
  try {
    assertNoteRequestHeaders(request.headers);
    const address = clientAddressFromHeaders(request.headers);
    const limit = await consumeRateLimit({
      scope: "public-notes-consume-ip",
      identifier: address,
      limit: 120,
      windowSeconds: 60 * 60,
    });
    if (!limit.allowed) {
      return NextResponse.json(
        { ok: false, message: "Слишком много запросов. Попробуйте позже." },
        {
          status: 429,
          headers: { ...NO_STORE_HEADERS, "Retry-After": limit.retryAfterSeconds.toString() },
        },
      );
    }

    const { id } = await context.params;
    const parsed = consumeSchema.safeParse(await request.json());
    if (!NOTE_ID_PATTERN.test(id) || !parsed.success) {
      return NextResponse.json(
        { ok: false, message: "Заметка недоступна." },
        { status: 404, headers: NO_STORE_HEADERS },
      );
    }
    const keyCommitment = Buffer.from(parsed.data.keyCommitment, "base64url");
    if (
      keyCommitment.byteLength !== 32 ||
      keyCommitment.toString("base64url") !== parsed.data.keyCommitment
    ) {
      return NextResponse.json(
        { ok: false, message: "Заметка недоступна." },
        { status: 404, headers: NO_STORE_HEADERS },
      );
    }
    const note = await consumeEncryptedNote(id, keyCommitment);
    if (!note) {
      return NextResponse.json(
        { ok: false, message: "Заметка уже прочитана, удалена или срок хранения истёк." },
        { status: 404, headers: NO_STORE_HEADERS },
      );
    }
    return NextResponse.json(
      { ok: true, ...note },
      { headers: NO_STORE_HEADERS },
    );
  } catch (error) {
    const status = error instanceof RequestSecurityError ? error.status : 503;
    return NextResponse.json(
      { ok: false, message: status === 503 ? "Сервис временно недоступен." : "Запрос отклонён." },
      { status, headers: NO_STORE_HEADERS },
    );
  }
}
