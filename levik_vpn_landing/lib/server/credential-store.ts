import "server-only";

import {
  decodeSecret,
  decryptString,
  encryptString,
} from "@/lib/server/crypto";
import { query } from "@/lib/server/db";
import { getEnvironment } from "@/lib/server/env";

type CredentialKind = "free_proxy";

function purpose(userKey: string, kind: CredentialKind): string {
  return `ephemeral-credential:v1:${userKey}:${kind}`;
}

export async function storeEphemeralCredential(
  userKey: string,
  kind: CredentialKind,
  value: string,
  expiresAt: Date,
): Promise<void> {
  const ciphertext = encryptString(
    value,
    decodeSecret(getEnvironment().SESSION_ENCRYPTION_KEY),
    purpose(userKey, kind),
  );
  await query(
    `
      INSERT INTO web_ephemeral_credentials (
        user_key,
        credential_kind,
        credential_ciphertext,
        expires_at
      )
      VALUES ($1, $2, $3, $4)
      ON CONFLICT (user_key, credential_kind)
      DO UPDATE SET
        credential_ciphertext = EXCLUDED.credential_ciphertext,
        created_at = now(),
        expires_at = EXCLUDED.expires_at
    `,
    [userKey, kind, ciphertext, expiresAt],
  );
}

export async function getEphemeralCredential(
  userKey: string,
  kind: CredentialKind,
): Promise<string | null> {
  const result = await query<{ credential_ciphertext: string }>(
    `
      SELECT credential_ciphertext
      FROM web_ephemeral_credentials
      WHERE user_key = $1
        AND credential_kind = $2
        AND expires_at > now()
      LIMIT 1
    `,
    [userKey, kind],
  );
  const ciphertext = result.rows[0]?.credential_ciphertext;
  if (!ciphertext) {
    return null;
  }
  try {
    return decryptString(
      ciphertext,
      decodeSecret(getEnvironment().SESSION_ENCRYPTION_KEY),
      purpose(userKey, kind),
    );
  } catch {
    return null;
  }
}
