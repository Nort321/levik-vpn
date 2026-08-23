import "server-only";

import type { PoolClient } from "pg";

import { generateLevikId, cleanDisplayText } from "@/lib/server/account/identifiers";
import { query } from "@/lib/server/db";

export type AccountStatus =
  | "active"
  | "deletion_pending"
  | "suspended"
  | "deleted";

export type AccountRecord = {
  accountId: string;
  levikId: string;
  displayName: string;
  status: AccountStatus;
  createdAt: Date;
};

type AccountRow = {
  account_id: string;
  levik_id: string;
  display_name: string;
  status: AccountStatus;
  created_at: Date;
};

function record(row: AccountRow): AccountRecord {
  return {
    accountId: row.account_id,
    levikId: row.levik_id,
    displayName: row.display_name,
    status: row.status,
    createdAt: row.created_at,
  };
}

export function publicAccount(account: AccountRecord) {
  return {
    id: account.accountId,
    levikId: account.levikId,
    displayName: account.displayName,
    status: account.status,
    createdAt: account.createdAt.toISOString(),
  };
}

export async function createAccount(
  client: PoolClient,
  displayName: string,
): Promise<AccountRecord> {
  const cleanedName = cleanDisplayText(displayName, 120, "Levik user");
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const result = await client.query<AccountRow>(
      `
        INSERT INTO accounts (levik_id, display_name)
        VALUES ($1, $2)
        ON CONFLICT (levik_id) DO NOTHING
        RETURNING account_id, levik_id, display_name, status, created_at
      `,
      [generateLevikId(), cleanedName],
    );
    const row = result.rows[0];
    if (row) {
      return record(row);
    }
  }
  throw new Error("Unable to allocate a unique Levik ID");
}

export async function getAccountById(
  accountId: string,
): Promise<AccountRecord | null> {
  const result = await query<AccountRow>(
    `
      SELECT account_id, levik_id, display_name, status, created_at
      FROM accounts
      WHERE account_id = $1
      LIMIT 1
    `,
    [accountId],
  );
  return result.rows[0] ? record(result.rows[0]) : null;
}

export async function getAccountByLevikId(
  levikId: string,
): Promise<AccountRecord | null> {
  const result = await query<AccountRow>(
    `
      SELECT account_id, levik_id, display_name, status, created_at
      FROM accounts
      WHERE levik_id = $1
      LIMIT 1
    `,
    [levikId],
  );
  return result.rows[0] ? record(result.rows[0]) : null;
}
