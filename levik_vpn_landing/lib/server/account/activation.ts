import "server-only";

import { AccountApiError } from "@/lib/server/account/errors";
import { issueAccountBridgeGrant } from "@/lib/server/account/bridge";
import {
  generateActivationCode,
  normalizeActivationCode,
} from "@/lib/server/account/identifiers";
import { rememberAccountBridgePrincipal } from "@/lib/server/account/legacy";
import { sha256 } from "@/lib/server/crypto";
import { query } from "@/lib/server/db";
import { getEnvironment } from "@/lib/server/env";

type ActivationRow = {
  activation_id: string;
  account_id: string | null;
  login_token_hash: Buffer;
  expires_at: Date;
  authorized_at: Date | null;
  consumed_at: Date | null;
  device_label: string;
};

type AuthorizedActivationRow = {
  activation_id: string;
  account_id: string;
};

export async function createAccountActivation(
  loginToken: string,
  expiresAt: Date,
): Promise<{ code: string; uri: string }> {
  const code = generateActivationCode();
  await query(
    `
      INSERT INTO account_activations (
        login_token_hash,
        code_hash,
        expires_at
      )
      VALUES ($1, $2, $3)
      ON CONFLICT (login_token_hash)
      DO UPDATE SET
        code_hash = EXCLUDED.code_hash,
        account_id = NULL,
        expires_at = EXCLUDED.expires_at,
        authorized_at = NULL,
        consumed_at = NULL,
        created_at = now()
    `,
    [sha256(loginToken), sha256(code), expiresAt],
  );
  const uri = new URL("/activate", getEnvironment().APP_ORIGIN);
  uri.searchParams.set("code", code);
  return { code, uri: uri.toString() };
}

export async function getAccountActivation(codeInput: string) {
  const code = normalizeActivationCode(codeInput);
  const result = await query<ActivationRow>(
    `
      SELECT
        activation.activation_id,
        activation.account_id,
        activation.login_token_hash,
        activation.expires_at,
        activation.authorized_at,
        activation.consumed_at,
        binding.device_label
      FROM account_activations AS activation
      INNER JOIN mobile_login_bindings AS binding
        ON binding.login_token_hash = activation.login_token_hash
      WHERE activation.code_hash = $1
        AND activation.consumed_at IS NULL
        AND activation.expires_at > now()
      LIMIT 1
    `,
    [sha256(code)],
  );
  const row = result.rows[0];
  if (!row) {
    throw new AccountApiError("activation_not_found", 404);
  }
  return {
    code,
    expiresAt: row.expires_at.toISOString(),
    device: {
      name: row.device_label,
      platform: "android" as const,
    },
  };
}

export async function authorizeAccountActivation(
  accountId: string,
  codeInput: string,
): Promise<void> {
  const code = normalizeActivationCode(codeInput);
  const result = await query<{ account_id: string }>(
    `
      UPDATE account_activations
      SET account_id = $2, authorized_at = now()
      WHERE code_hash = $1
        AND consumed_at IS NULL
        AND expires_at > now()
        AND (account_id IS NULL OR account_id = $2)
      RETURNING account_id
    `,
    [sha256(code), accountId],
  );
  if (result.rowCount !== 1) {
    throw new AccountApiError("activation_not_found", 404);
  }
}

export async function getAuthorizedAccountActivation(
  loginToken: string,
): Promise<AuthorizedActivationRow | null> {
  const result = await query<AuthorizedActivationRow>(
    `
      SELECT activation_id, account_id
      FROM account_activations
      WHERE login_token_hash = $1
        AND authorized_at IS NOT NULL
        AND consumed_at IS NULL
        AND expires_at > now()
      LIMIT 1
    `,
    [sha256(loginToken)],
  );
  return result.rows[0] ?? null;
}

export async function issueBridgeAuthorizationForAccount(
  activation: AuthorizedActivationRow,
) {
  const legacy = await query<{ user_key: string }>(
    `
      SELECT user_key
      FROM legacy_account_links
      WHERE account_id = $1 AND revoked_at IS NULL
      LIMIT 1
    `,
    [activation.account_id],
  );
  const legacyUserKey = legacy.rows[0]?.user_key ?? null;
  const response = await issueAccountBridgeGrant({
    accountId: activation.account_id,
    legacyUserKey,
    idempotencyKey: activation.activation_id,
  });
  await rememberAccountBridgePrincipal(
    activation.account_id,
    response.user.userKey,
    legacyUserKey,
  );
  return {
    ok: true as const,
    status: "authorized" as const,
    grant: response.grant,
    grantExpiresIn: response.grantExpiresIn,
    user: response.user,
  };
}

export async function markAccountActivationConsumed(
  activationId: string,
): Promise<void> {
  await query(
    `
      UPDATE account_activations
      SET consumed_at = now()
      WHERE activation_id = $1 AND consumed_at IS NULL
    `,
    [activationId],
  );
}
