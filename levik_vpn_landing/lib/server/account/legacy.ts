import "server-only";

import type { PoolClient } from "pg";

import type { AuthenticatedSession } from "@/lib/server/browser-auth";
import { AccountApiError, isPostgresError } from "@/lib/server/account/errors";
import { cleanDisplayText } from "@/lib/server/account/identifiers";
import { insertIdentity } from "@/lib/server/account/identity";
import { createAccount } from "@/lib/server/account/model";
import { linkLegacyAccountSession } from "@/lib/server/account/session";
import type { AuthenticatedAccountSession } from "@/lib/server/account/session";
import { ensureAccountBridgeSession } from "@/lib/server/account/bridge-session";
import { query, withTransaction } from "@/lib/server/db";

type LegacyLinkRow = {
  account_id: string;
};

type LegacyReservationRow = {
  account_id: string;
  user_key: string;
  state: "pending" | "committed";
};

export async function accountIdForLegacyUserKey(
  userKey: string,
): Promise<string | null> {
  const result = await query<LegacyLinkRow>(
    `
      SELECT account_id
      FROM legacy_account_links
      WHERE user_key = $1 AND revoked_at IS NULL
      LIMIT 1
    `,
    [userKey],
  );
  return result.rows[0]?.account_id ?? null;
}

async function reservationForLegacyUserKey(
  userKey: string,
): Promise<LegacyReservationRow | null> {
  const result = await query<LegacyReservationRow>(
    `
      SELECT account_id, user_key, state
      FROM account_legacy_link_reservations
      WHERE user_key = $1
      LIMIT 1
    `,
    [userKey],
  );
  return result.rows[0] ?? null;
}

async function reserveLegacyLinkWithClient(
  client: PoolClient,
  accountId: string,
  userKey: string,
): Promise<void> {
  const account = await client.query(
    `
      SELECT account_id
      FROM accounts
      WHERE account_id = $1 AND status <> 'deleted'
      FOR UPDATE
    `,
    [accountId],
  );
  if (account.rowCount !== 1) {
    throw new AccountApiError("account_not_found", 404);
  }
  const existingLink = await client.query<{ account_id: string; user_key: string }>(
    `
      SELECT account_id, user_key
      FROM legacy_account_links
      WHERE (account_id = $1 OR user_key = $2) AND revoked_at IS NULL
      FOR UPDATE
    `,
    [accountId, userKey],
  );
  if (
    existingLink.rows.some(
      (row) => row.account_id !== accountId || row.user_key !== userKey,
    )
  ) {
    throw new AccountApiError("credential_conflict", 409);
  }
  const state = existingLink.rows.length > 0 ? "committed" : "pending";
  const reservation = await client.query<LegacyReservationRow>(
    `
      INSERT INTO account_legacy_link_reservations (
        account_id,
        user_key,
        state
      )
      VALUES ($1, $2, $3)
      ON CONFLICT (account_id)
      DO UPDATE SET
        state = CASE
          WHEN account_legacy_link_reservations.state = 'committed'
            THEN 'committed'
          ELSE EXCLUDED.state
        END,
        updated_at = now()
      WHERE account_legacy_link_reservations.user_key = EXCLUDED.user_key
      RETURNING account_id, user_key, state
    `,
    [accountId, userKey, state],
  );
  if (
    reservation.rowCount !== 1 ||
    reservation.rows[0]?.account_id !== accountId ||
    reservation.rows[0]?.user_key !== userKey
  ) {
    throw new AccountApiError("credential_conflict", 409);
  }
}

async function reserveLegacyLink(
  accountId: string,
  userKey: string,
): Promise<void> {
  try {
    await withTransaction((client) =>
      reserveLegacyLinkWithClient(client, accountId, userKey),
    );
  } catch (error) {
    if (isPostgresError(error, "23505")) {
      throw new AccountApiError("credential_conflict", 409);
    }
    throw error;
  }
}

async function insertLegacyLinkWithClient(
  client: PoolClient,
  accountId: string,
  userKey: string,
  label: string,
): Promise<void> {
  await insertIdentity(client, {
    accountId,
    provider: "telegram",
    subject: userKey,
    label,
  });
  await client.query(
    `
      INSERT INTO legacy_account_links (account_id, user_key)
      VALUES ($1, $2)
    `,
    [accountId, userKey],
  );
  await client.query(
    `
      UPDATE web_sessions
      SET account_id = $2
      WHERE user_key = $1 AND (account_id IS NULL OR account_id = $2)
    `,
    [userKey, accountId],
  );
}

async function commitReservedLegacyLink(
  accountId: string,
  userKey: string,
  label: string,
  bridgeUserKey: string,
): Promise<void> {
  try {
    await withTransaction(async (client) => {
      const reservation = await client.query<LegacyReservationRow>(
        `
          SELECT account_id, user_key, state
          FROM account_legacy_link_reservations
          WHERE account_id = $1
          FOR UPDATE
        `,
        [accountId],
      );
      if (reservation.rows[0]?.user_key !== userKey) {
        throw new AccountApiError("credential_conflict", 409);
      }
      const bridge = await client.query<{
        bridge_user_key: string;
        requested_legacy_user_key: string | null;
        state: string;
      }>(
        `
          SELECT
            principal.bridge_user_key,
            authorization.requested_legacy_user_key,
            authorization.state
          FROM account_bridge_authorizations AS authorization
          INNER JOIN account_bridge_principals AS principal
            ON principal.account_id = authorization.account_id
          WHERE authorization.account_id = $1
          FOR UPDATE OF authorization, principal
        `,
        [accountId],
      );
      const binding = bridge.rows[0];
      if (
        !binding ||
        binding.state !== "active" ||
        binding.requested_legacy_user_key !== userKey ||
        binding.bridge_user_key !== bridgeUserKey
      ) {
        throw new AccountApiError("credential_conflict", 409);
      }
      const accountLink = await client.query<{ user_key: string }>(
        `
          SELECT user_key
          FROM legacy_account_links
          WHERE account_id = $1 AND revoked_at IS NULL
          FOR UPDATE
        `,
        [accountId],
      );
      if (accountLink.rows[0]?.user_key !== undefined) {
        if (accountLink.rows[0].user_key !== userKey) {
          throw new AccountApiError("credential_conflict", 409);
        }
      } else {
        await insertLegacyLinkWithClient(client, accountId, userKey, label);
      }
      await client.query(
        `
          UPDATE account_legacy_link_reservations
          SET state = 'committed', updated_at = now()
          WHERE account_id = $1 AND user_key = $2
        `,
        [accountId, userKey],
      );
    });
  } catch (error) {
    if (isPostgresError(error, "23505")) {
      throw new AccountApiError("credential_conflict", 409);
    }
    throw error;
  }
}

export async function ensureLegacyAccount(
  session: AuthenticatedSession,
): Promise<string> {
  const existing = await accountIdForLegacyUserKey(session.userKey);
  if (existing) {
    await linkLegacyAccountSession(existing, session);
    return existing;
  }

  if (await reservationForLegacyUserKey(session.userKey)) {
    throw new AccountApiError("credential_conflict", 409);
  }

  try {
    const account = await withTransaction(async (client) => {
      const created = await createAccount(
        client,
        cleanDisplayText(session.userLabel, 120, "Telegram user"),
      );
      await reserveLegacyLinkWithClient(
        client,
        created.accountId,
        session.userKey,
      );
      await insertLegacyLinkWithClient(
        client,
        created.accountId,
        session.userKey,
        "Telegram legacy account",
      );
      await client.query(
        `
          UPDATE account_legacy_link_reservations
          SET state = 'committed', updated_at = now()
          WHERE account_id = $1 AND user_key = $2
        `,
        [created.accountId, session.userKey],
      );
      return created;
    });
    await linkLegacyAccountSession(account.accountId, session);
    return account.accountId;
  } catch (error) {
    if (!isPostgresError(error, "23505") && !(error instanceof AccountApiError)) {
      throw error;
    }
    const concurrent = await accountIdForLegacyUserKey(session.userKey);
    if (!concurrent) {
      if (await reservationForLegacyUserKey(session.userKey)) {
        throw new AccountApiError("credential_conflict", 409);
      }
      throw error;
    }
    await linkLegacyAccountSession(concurrent, session);
    return concurrent;
  }
}

export async function linkLegacyIdentity(
  accountId: string,
  accountSession: AuthenticatedAccountSession,
  legacySession: AuthenticatedSession,
): Promise<void> {
  const linkedForUser = await accountIdForLegacyUserKey(legacySession.userKey);
  if (linkedForUser && linkedForUser !== accountId) {
    throw new AccountApiError("credential_conflict", 409);
  }
  await reserveLegacyLink(accountId, legacySession.userKey);
  const bridgeSession = await ensureAccountBridgeSession(accountSession, {
    legacyUserKey: legacySession.userKey,
  });
  await commitReservedLegacyLink(
    accountId,
    legacySession.userKey,
    "Telegram legacy account",
    bridgeSession.userKey,
  );
  await linkLegacyAccountSession(accountId, legacySession);
}

export async function rememberAccountBridgePrincipal(
  accountId: string,
  userKey: string,
  requestedLegacyUserKey: string | null = null,
): Promise<void> {
  try {
    await withTransaction(async (client) => {
      const current = await client.query<{ bridge_user_key: string }>(
      `
        SELECT bridge_user_key FROM account_bridge_principals
        WHERE account_id = $1
        FOR UPDATE
      `,
      [accountId],
      );
      const existingUserKey = current.rows[0]?.bridge_user_key;
      if (existingUserKey && existingUserKey !== userKey) {
        if (requestedLegacyUserKey !== userKey) {
          throw new AccountApiError("credential_conflict", 409);
        }
        await client.query(
          `
            UPDATE account_bridge_principals
            SET bridge_user_key = $2, updated_at = now()
            WHERE account_id = $1 AND bridge_user_key = $3
          `,
          [accountId, userKey, existingUserKey],
        );
      }
      if (!existingUserKey) {
        if (requestedLegacyUserKey !== null && requestedLegacyUserKey !== userKey) {
          throw new AccountApiError("credential_conflict", 409);
        }
        await client.query(
        `
          INSERT INTO account_bridge_principals (account_id, bridge_user_key)
          VALUES ($1, $2)
        `,
        [accountId, userKey],
        );
      }
    });
  } catch (error) {
    if (isPostgresError(error, "23505")) {
      throw new AccountApiError("credential_conflict", 409);
    }
    throw error;
  }
}

export async function upsertAccountDevice(input: {
  accountId: string;
  externalDeviceId: string;
  name: string;
  platform: "android" | "browser" | "unknown";
}): Promise<void> {
  await query(
    `
      INSERT INTO account_devices (
        account_id,
        external_device_id,
        name,
        platform
      )
      VALUES ($1, $2, $3, $4)
      ON CONFLICT (account_id, external_device_id)
      DO UPDATE SET
        name = EXCLUDED.name,
        platform = EXCLUDED.platform,
        last_seen_at = now(),
        revoked_at = NULL
    `,
    [
      input.accountId,
      input.externalDeviceId,
      cleanDisplayText(input.name, 120, "Android device"),
      input.platform,
    ],
  );
}

export async function revokeAccountDevice(
  accountId: string,
  deviceId: string,
): Promise<boolean> {
  return withTransaction(async (client) => {
    const selected = await client.query<{ external_device_id: string }>(
      `
        SELECT external_device_id
        FROM account_devices
        WHERE account_id = $1 AND device_id = $2 AND revoked_at IS NULL
        FOR UPDATE
      `,
      [accountId, deviceId],
    );
    const externalDeviceId = selected.rows[0]?.external_device_id;
    if (!externalDeviceId) {
      return false;
    }
    await client.query(
      `
        UPDATE account_devices
        SET revoked_at = now()
        WHERE account_id = $1 AND device_id = $2
      `,
      [accountId, deviceId],
    );
    await client.query(
      `
        INSERT INTO web_grant_revocations (session_token_hash, idempotency_key)
        SELECT session.token_hash, gen_random_uuid()
        FROM web_sessions AS session
        INNER JOIN mobile_session_bindings AS binding
          ON binding.session_token_hash = session.token_hash
        WHERE session.account_id = $1
          AND binding.device_id = $2
          AND session.revoked_at IS NULL
        ON CONFLICT (session_token_hash) DO NOTHING
      `,
      [accountId, externalDeviceId],
    );
    await client.query(
      `
        UPDATE web_sessions AS session
        SET revoked_at = now()
        FROM mobile_session_bindings AS binding
        WHERE binding.session_token_hash = session.token_hash
          AND session.account_id = $1
          AND binding.device_id = $2
          AND session.revoked_at IS NULL
      `,
      [accountId, externalDeviceId],
    );
    await client.query(
      `
        UPDATE account_sessions AS account_session
        SET revoked_at = now()
        FROM mobile_session_bindings AS binding
        WHERE binding.session_token_hash = account_session.token_hash
          AND account_session.account_id = $1
          AND binding.device_id = $2
          AND account_session.revoked_at IS NULL
      `,
      [accountId, externalDeviceId],
    );
    return true;
  });
}
