import "server-only";

import { query } from "@/lib/server/db";

export type StoredNotePayload = {
  iv: string;
  ciphertext: string;
};

export async function createEncryptedNote(input: {
  id: string;
  keyCommitment: Buffer;
  iv: Buffer;
  ciphertext: Buffer;
  expiresInDays: number;
}): Promise<Date | null> {
  const result = await query<{ expires_at: Date }>(
    `
      INSERT INTO encrypted_notes (
        id,
        key_commitment,
        iv,
        ciphertext,
        expires_at
      )
      VALUES ($1, $2, $3, $4, now() + ($5 * interval '1 day'))
      ON CONFLICT (id) DO NOTHING
      RETURNING expires_at
    `,
    [input.id, input.keyCommitment, input.iv, input.ciphertext, input.expiresInDays],
  );
  return result.rows[0]?.expires_at ?? null;
}

export async function consumeEncryptedNote(
  id: string,
  keyCommitment: Buffer,
): Promise<StoredNotePayload | null> {
  const result = await query<{ iv: Buffer; ciphertext: Buffer }>(
    `
      DELETE FROM encrypted_notes
      WHERE id = $1
        AND key_commitment = $2
        AND expires_at > now()
      RETURNING iv, ciphertext
    `,
    [id, keyCommitment],
  );
  const note = result.rows[0];
  if (!note) return null;
  return {
    iv: note.iv.toString("base64url"),
    ciphertext: note.ciphertext.toString("base64url"),
  };
}
