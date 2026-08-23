import { NextResponse } from "next/server";
import { z } from "zod";

import { MAX_NOTE_PLAINTEXT_BYTES, NOTE_ID_PATTERN } from "@/lib/notes/crypto";
import { assertNoteRequestHeaders } from "@/lib/server/note-request";
import { createEncryptedNote } from "@/lib/server/notes";
import { consumeRateLimit } from "@/lib/server/rate-limit";
import { clientAddressFromHeaders, RequestSecurityError } from "@/lib/server/security";

export const dynamic = "force-dynamic";

const MAX_CIPHERTEXT_BYTES = MAX_NOTE_PLAINTEXT_BYTES + 16;
const NO_STORE_HEADERS = {
  "Cache-Control": "private, no-store, max-age=0",
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff",
} as const;

const createNoteSchema = z.object({
  id: z.string().regex(NOTE_ID_PATTERN),
  keyCommitment: z.string().regex(/^[A-Za-z0-9_-]{43}$/),
  iv: z.string().regex(/^[A-Za-z0-9_-]{16}$/),
  ciphertext: z.string().min(23).max(16_022).regex(/^[A-Za-z0-9_-]+$/),
  expiresInDays: z.number().int().min(1).max(30),
}).strict();

function canonicalBytes(value: string, expectedLength?: number): Buffer | null {
  const bytes = Buffer.from(value, "base64url");
  if (
    bytes.toString("base64url") !== value ||
    (expectedLength !== undefined && bytes.byteLength !== expectedLength)
  ) {
    return null;
  }
  return bytes;
}

export async function POST(request: Request) {
  try {
    assertNoteRequestHeaders(request.headers);
    const address = clientAddressFromHeaders(request.headers);
    const limit = await consumeRateLimit({
      scope: "public-notes-create-ip",
      identifier: address,
      limit: 40,
      windowSeconds: 60 * 60,
    });
    if (!limit.allowed) {
      return NextResponse.json(
        { ok: false, message: "Слишком много заметок. Попробуйте позже." },
        {
          status: 429,
          headers: { ...NO_STORE_HEADERS, "Retry-After": limit.retryAfterSeconds.toString() },
        },
      );
    }

    const parsed = createNoteSchema.safeParse(await request.json());
    if (!parsed.success) {
      return NextResponse.json(
        { ok: false, message: "Некорректные данные заметки." },
        { status: 400, headers: NO_STORE_HEADERS },
      );
    }
    const keyCommitment = canonicalBytes(parsed.data.keyCommitment, 32);
    const iv = canonicalBytes(parsed.data.iv, 12);
    const ciphertext = canonicalBytes(parsed.data.ciphertext);
    if (
      !keyCommitment ||
      !iv ||
      !ciphertext ||
      ciphertext.byteLength < 17 ||
      ciphertext.byteLength > MAX_CIPHERTEXT_BYTES
    ) {
      return NextResponse.json(
        { ok: false, message: "Некорректные данные заметки." },
        { status: 400, headers: NO_STORE_HEADERS },
      );
    }

    const expiresAt = await createEncryptedNote({
      id: parsed.data.id,
      keyCommitment,
      iv,
      ciphertext,
      expiresInDays: parsed.data.expiresInDays,
    });
    if (!expiresAt) {
      return NextResponse.json(
        { ok: false, message: "Не удалось создать заметку. Повторите попытку." },
        { status: 409, headers: NO_STORE_HEADERS },
      );
    }
    return NextResponse.json(
      { ok: true, expiresAt: expiresAt.toISOString() },
      { status: 201, headers: NO_STORE_HEADERS },
    );
  } catch (error) {
    const status = error instanceof RequestSecurityError ? error.status : 503;
    return NextResponse.json(
      { ok: false, message: status === 503 ? "Сервис временно недоступен." : "Запрос отклонён." },
      { status, headers: NO_STORE_HEADERS },
    );
  }
}
