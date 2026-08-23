import "server-only";

import { randomUUID } from "node:crypto";

import type { PoolClient } from "pg";

import { AccountApiError } from "@/lib/server/account/errors";
import {
  generateRecoveryCode,
  normalizeRecoveryCode,
} from "@/lib/server/account/identifiers";
import { type AccountRecord } from "@/lib/server/account/model";
import { decodeSecret, hmacBase64Url } from "@/lib/server/crypto";
import { query, withTransaction } from "@/lib/server/db";
import { getEnvironment } from "@/lib/server/env";

const RECOVERY_CODE_COUNT = 10;

function recoveryHash(code: string): Buffer {
  return Buffer.from(
    hmacBase64Url(
      decodeSecret(getEnvironment().AUDIT_HMAC_KEY),
      `account-recovery-code:v1:${code}`,
    ),
    "base64url",
  );
}

export async function regenerateRecoveryCodes(
  accountId: string,
): Promise<string[]> {
  return withTransaction((client) =>
    replaceRecoveryCodesWithClient(client, accountId),
  );
}

export async function replaceRecoveryCodesWithClient(
  client: PoolClient,
  accountId: string,
): Promise<string[]> {
  const codes = Array.from({ length: RECOVERY_CODE_COUNT }, () =>
    generateRecoveryCode(),
  );
  const batchId = randomUUID();
  await client.query(
    `
      UPDATE recovery_codes
      SET revoked_at = now()
      WHERE account_id = $1 AND used_at IS NULL AND revoked_at IS NULL
    `,
    [accountId],
  );
  for (const code of codes) {
    await client.query(
      `
        INSERT INTO recovery_codes (batch_id, account_id, code_hash)
        VALUES ($1, $2, $3)
      `,
      [batchId, accountId, recoveryHash(code)],
    );
  }
  return codes;
}

export async function authenticateRecoveryCode(
  levikId: string,
  suppliedCode: string,
): Promise<AccountRecord> {
  const code = normalizeRecoveryCode(suppliedCode);
  const result = await query<{
    account_id: string;
    levik_id: string;
    display_name: string;
    status: AccountRecord["status"];
    created_at: Date;
  }>(
    `
      UPDATE recovery_codes AS recovery
      SET used_at = now()
      FROM accounts AS account
      WHERE recovery.account_id = account.account_id
        AND account.levik_id = $1
        AND account.status IN ('active', 'deletion_pending')
        AND recovery.code_hash = $2
        AND recovery.used_at IS NULL
        AND recovery.revoked_at IS NULL
      RETURNING
        account.account_id,
        account.levik_id,
        account.display_name,
        account.status,
        account.created_at
    `,
    [levikId, recoveryHash(code)],
  );
  const row = result.rows[0];
  if (!row) {
    throw new AccountApiError("invalid_credentials", 401);
  }
  return {
    accountId: row.account_id,
    levikId: row.levik_id,
    displayName: row.display_name,
    status: row.status,
    createdAt: row.created_at,
  };
}
