import "server-only";

import type { PoolClient } from "pg";

import { AccountApiError, isPostgresError } from "@/lib/server/account/errors";
import { cleanDisplayText } from "@/lib/server/account/identifiers";
import { query, withTransaction } from "@/lib/server/db";

export type IdentityProvider = "google" | "telegram" | "password";

export async function insertIdentity(
  client: PoolClient,
  input: {
    accountId: string;
    provider: IdentityProvider;
    subject: string;
    label: string;
  },
): Promise<string> {
  try {
    const result = await client.query<{ identity_id: string }>(
      `
        INSERT INTO account_identities (
          account_id,
          provider,
          provider_subject,
          label
        )
        VALUES ($1, $2, $3, $4)
        RETURNING identity_id
      `,
      [
        input.accountId,
        input.provider,
        input.subject,
        cleanDisplayText(input.label, 120, input.provider),
      ],
    );
    const identityId = result.rows[0]?.identity_id;
    if (!identityId) {
      throw new Error("Identity insert did not return a row");
    }
    return identityId;
  } catch (error) {
    if (isPostgresError(error, "23505")) {
      throw new AccountApiError("credential_conflict", 409);
    }
    throw error;
  }
}

export async function touchIdentity(
  accountId: string,
  provider: IdentityProvider,
  subject: string,
): Promise<void> {
  await query(
    `
      UPDATE account_identities
      SET last_used_at = now()
      WHERE account_id = $1
        AND provider = $2
        AND provider_subject = $3
        AND revoked_at IS NULL
    `,
    [accountId, provider, subject],
  );
}

export async function revokeIdentity(
  accountId: string,
  identityId: string,
): Promise<void> {
  await withTransaction(async (client) => {
    const selected = await client.query<{
      identity_id: string;
      provider: IdentityProvider;
      provider_subject: string;
    }>(
      `
        SELECT identity_id, provider, provider_subject
        FROM account_identities
        WHERE identity_id = $1
          AND account_id = $2
          AND revoked_at IS NULL
        FOR UPDATE
      `,
      [identityId, accountId],
    );
    const identity = selected.rows[0];
    if (!identity) {
      throw new AccountApiError("identity_not_found", 404);
    }

    const methods = await client.query<{ method_count: number }>(
      `
        SELECT (
          SELECT count(*)::integer
          FROM account_identities
          WHERE account_id = $1
            AND revoked_at IS NULL
            AND identity_id <> $2
        ) + (
          SELECT count(*)::integer
          FROM passkey_credentials
          WHERE account_id = $1 AND revoked_at IS NULL
        ) + (
          SELECT count(*)::integer
          FROM recovery_codes
          WHERE account_id = $1 AND used_at IS NULL AND revoked_at IS NULL
        ) AS method_count
      `,
      [accountId, identityId],
    );
    if ((methods.rows[0]?.method_count ?? 0) < 1) {
      throw new AccountApiError("last_authentication_method", 409);
    }

    await client.query(
      `
        UPDATE account_identities
        SET revoked_at = now(), provider_subject = 'revoked:' || identity_id::text
        WHERE identity_id = $1 AND account_id = $2
      `,
      [identityId, accountId],
    );
    if (identity.provider === "password") {
      await client.query(
        "DELETE FROM password_credentials WHERE account_id = $1",
        [accountId],
      );
    } else if (identity.provider === "telegram") {
      await client.query(
        `
          INSERT INTO web_grant_revocations (
            session_token_hash,
            idempotency_key
          )
          SELECT token_hash, gen_random_uuid()
          FROM web_sessions
          WHERE account_id = $1
            AND user_key = $2
            AND session_kind = 'legacy'
            AND revoked_at IS NULL
          ON CONFLICT (session_token_hash) DO NOTHING
        `,
        [accountId, identity.provider_subject],
      );
      await client.query(
        `
          UPDATE web_sessions
          SET revoked_at = COALESCE(revoked_at, now())
          WHERE account_id = $1
            AND user_key = $2
            AND session_kind = 'legacy'
        `,
        [accountId, identity.provider_subject],
      );
      await client.query(
        `
          UPDATE account_sessions
          SET revoked_at = COALESCE(revoked_at, now())
          WHERE account_id = $1 AND auth_method = 'telegram'
        `,
        [accountId],
      );
      await client.query(
        `
          UPDATE legacy_account_links
          SET
            user_key = 'revoked:' || $3::uuid::text,
            revoked_at = COALESCE(revoked_at, now())
          WHERE account_id = $1
            AND user_key = $2
            AND revoked_at IS NULL
        `,
        [accountId, identity.provider_subject, identity.identity_id],
      );
    }
  });
}
