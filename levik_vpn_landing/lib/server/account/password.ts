import "server-only";

import { randomBytes, scrypt, timingSafeEqual } from "node:crypto";

import type { PoolClient } from "pg";

import { writeAuditEvent } from "@/lib/server/audit";
import { AccountApiError } from "@/lib/server/account/errors";
import { insertIdentity } from "@/lib/server/account/identity";
import type { AccountRecord } from "@/lib/server/account/model";
import { query, withTransaction } from "@/lib/server/db";

const SCRYPT_PARAMETERS = Object.freeze({
  N: 65_536,
  r: 8,
  p: 1,
  keyLength: 32,
  maxmem: 96 * 1024 * 1024,
});

const DUMMY_SALT = Buffer.from("8db9fe8937ebc7677101c574e189f695", "hex");
const DUMMY_HASH = Buffer.alloc(SCRYPT_PARAMETERS.keyLength, 0x5a);

type PasswordRow = {
  account_id: string;
  levik_id: string;
  display_name: string;
  status: AccountRecord["status"];
  account_created_at: Date;
  salt: Buffer;
  derived_key: Buffer;
  algorithm: string;
  parameters: unknown;
  failed_attempts: number;
  locked_until: Date | null;
};

export type PasswordMaterial = {
  salt: Buffer;
  derivedKey: Buffer;
  parameters: Readonly<Record<string, number>>;
};

function hasExpectedParameters(value: unknown): boolean {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return false;
  }
  const parameters = value as Record<string, unknown>;
  return (
    parameters.N === SCRYPT_PARAMETERS.N &&
    parameters.r === SCRYPT_PARAMETERS.r &&
    parameters.p === SCRYPT_PARAMETERS.p &&
    parameters.keyLength === SCRYPT_PARAMETERS.keyLength
  );
}

async function denyPassword(accountId?: string): Promise<never> {
  await writeAuditEvent({
    eventType: "account.auth.password",
    outcome: "denied",
    accountId,
    metadata: { reason: "invalid_credentials", authMethod: "password" },
  }).catch(() => {});
  throw new AccountApiError("invalid_credentials", 401);
}

function validatedPassword(password: string): Buffer {
  const bytes = Buffer.from(password, "utf8");
  const characterLength = [...password].length;
  if (
    characterLength < 12 ||
    characterLength > 512 ||
    bytes.byteLength > 1_024 ||
    password.includes("\0")
  ) {
    throw new AccountApiError("invalid_password", 400);
  }
  return bytes;
}

function derive(password: Buffer, salt: Buffer): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    scrypt(
      password,
      salt,
      SCRYPT_PARAMETERS.keyLength,
      {
        N: SCRYPT_PARAMETERS.N,
        r: SCRYPT_PARAMETERS.r,
        p: SCRYPT_PARAMETERS.p,
        maxmem: SCRYPT_PARAMETERS.maxmem,
      },
      (error, key) => {
        if (error) {
          reject(error);
          return;
        }
        resolve(key);
      },
    );
  });
}

export async function hashPassword(password: string): Promise<PasswordMaterial> {
  const bytes = validatedPassword(password);
  const salt = randomBytes(16);
  return {
    salt,
    derivedKey: await derive(bytes, salt),
    parameters: {
      N: SCRYPT_PARAMETERS.N,
      r: SCRYPT_PARAMETERS.r,
      p: SCRYPT_PARAMETERS.p,
      keyLength: SCRYPT_PARAMETERS.keyLength,
    },
  };
}

export async function verifyPasswordHash(
  password: string,
  salt: Buffer,
  expected: Buffer,
): Promise<boolean> {
  let bytes: Buffer;
  try {
    bytes = validatedPassword(password);
  } catch {
    bytes = Buffer.from("invalid-password-shape", "utf8");
  }
  const actual = await derive(bytes, salt);
  return expected.byteLength === actual.byteLength && timingSafeEqual(expected, actual);
}

async function recordFailedPasswordAttempt(accountId: string): Promise<void> {
  await query(
    `
      UPDATE password_credentials
      SET
        failed_attempts = LEAST(failed_attempts + 1, 1000000),
        locked_until = CASE
          WHEN failed_attempts + 1 < 5 THEN locked_until
          ELSE now() + (
            LEAST(900, power(2, LEAST(failed_attempts - 3, 9)))
            * interval '1 second'
          )
        END,
        updated_at = now()
      WHERE account_id = $1
    `,
    [accountId],
  );
}

export async function authenticatePassword(
  levikId: string,
  password: string,
): Promise<AccountRecord> {
  const result = await query<PasswordRow>(
    `
      SELECT
        account.account_id,
        account.levik_id,
        account.display_name,
        account.status,
        account.created_at AS account_created_at,
        credential.salt,
        credential.derived_key,
        credential.algorithm,
        credential.parameters,
        credential.failed_attempts,
        credential.locked_until
      FROM accounts AS account
      INNER JOIN password_credentials AS credential
        ON credential.account_id = account.account_id
      INNER JOIN account_identities AS identity
        ON identity.account_id = account.account_id
        AND identity.provider = 'password'
        AND identity.revoked_at IS NULL
      WHERE account.levik_id = $1
      LIMIT 1
    `,
    [levikId],
  );
  const row = result.rows[0];
  if (!row) {
    await verifyPasswordHash(password, DUMMY_SALT, DUMMY_HASH);
    return denyPassword();
  }
  if (
    !["active", "deletion_pending"].includes(row.status) ||
    row.algorithm !== "scrypt-v1" ||
    !hasExpectedParameters(row.parameters) ||
    (row.locked_until?.getTime() ?? 0) > Date.now()
  ) {
    await verifyPasswordHash(password, row.salt, row.derived_key);
    return denyPassword(row.account_id);
  }

  const verified = await verifyPasswordHash(password, row.salt, row.derived_key);
  if (!verified) {
    await recordFailedPasswordAttempt(row.account_id);
    return denyPassword(row.account_id);
  }

  await withTransaction(async (client) => {
    await client.query(
      `
        UPDATE password_credentials
        SET failed_attempts = 0, locked_until = NULL, updated_at = now()
        WHERE account_id = $1
      `,
      [row.account_id],
    );
    await client.query(
      `
        UPDATE account_identities
        SET last_used_at = now()
        WHERE account_id = $1 AND provider = 'password' AND revoked_at IS NULL
      `,
      [row.account_id],
    );
  });

  return {
    accountId: row.account_id,
    levikId: row.levik_id,
    displayName: row.display_name,
    status: row.status,
    createdAt: row.account_created_at,
  };
}

export async function setPasswordCredential(
  accountId: string,
  password: string,
): Promise<void> {
  const hashed = await hashPassword(password);
  await withTransaction(async (client: PoolClient) => {
    const account = await client.query<{ levik_id: string }>(
      "SELECT levik_id FROM accounts WHERE account_id = $1 AND status IN ('active', 'deletion_pending') FOR UPDATE",
      [accountId],
    );
    const levikId = account.rows[0]?.levik_id;
    if (!levikId) {
      throw new AccountApiError("account_not_found", 404);
    }
    await setPasswordCredentialWithClient(client, accountId, levikId, hashed);
  });
}

export async function setPasswordCredentialWithClient(
  client: PoolClient,
  accountId: string,
  levikId: string,
  hashed: PasswordMaterial,
): Promise<void> {
  await client.query(
    `
      INSERT INTO password_credentials (
        account_id,
        salt,
        derived_key,
        parameters
      )
      VALUES ($1, $2, $3, $4::jsonb)
      ON CONFLICT (account_id)
      DO UPDATE SET
        salt = EXCLUDED.salt,
        derived_key = EXCLUDED.derived_key,
        parameters = EXCLUDED.parameters,
        failed_attempts = 0,
        locked_until = NULL,
        updated_at = now()
    `,
    [accountId, hashed.salt, hashed.derivedKey, JSON.stringify(hashed.parameters)],
  );
  const identity = await client.query(
    `
      SELECT 1 FROM account_identities
      WHERE account_id = $1 AND provider = 'password' AND revoked_at IS NULL
    `,
    [accountId],
  );
  if (identity.rowCount === 0) {
    await insertIdentity(client, {
      accountId,
      provider: "password",
      subject: levikId,
      label: "Levik ID + password",
    });
  }
}
