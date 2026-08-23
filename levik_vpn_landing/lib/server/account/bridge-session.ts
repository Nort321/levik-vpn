import "server-only";

import { randomBytes, randomUUID } from "node:crypto";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import type { PoolClient } from "pg";
import { cache } from "react";

import { AccountApiError, isPostgresError } from "@/lib/server/account/errors";
import { issueAccountBridgeGrant, type AccountBridgeAuthorization } from "@/lib/server/account/bridge";
import {
  ACCOUNT_SESSION_COOKIE_NAME,
  getAccountSessionByToken,
  type AuthenticatedAccountSession,
} from "@/lib/server/account/session";
import { getOptionalSession } from "@/lib/server/browser-auth";
import { revokeBridgeGrant } from "@/lib/server/bridge/auth";
import { BridgeError } from "@/lib/server/bridge/core";
import {
  constantTimeEqual,
  decodeSecret,
  decryptString,
  encryptString,
} from "@/lib/server/crypto";
import { query, withTransaction } from "@/lib/server/db";
import { getEnvironment } from "@/lib/server/env";
import type { AuthenticatedSession } from "@/lib/server/session-store";

const REFRESH_SKEW_MS = 60_000;
const REFRESH_LEASE_SECONDS = 20;

type AccountBridgeState = "pending" | "active" | "revoked";

type ActiveAccountRow = {
  display_name: string;
};

type AuthorizationRow = {
  account_id: string;
  bridge_session_token_hash: Buffer | null;
  idempotency_key: string;
  requested_legacy_user_key: string | null;
  state: AccountBridgeState;
  lease_token: string | null;
  lease_expires_at: Date | null;
  grant_expires_at: Date | null;
};

type ActiveGrantRow = AuthorizationRow & {
  bridge_user_key: string | null;
  shadow_user_key: string | null;
  user_label: string | null;
  grant_ciphertext: string | null;
  shadow_grant_expires_at: Date | null;
  shadow_revoked_at: Date | null;
};

type GrantClaim = {
  accountSession: AuthenticatedAccountSession;
  accountDisplayName: string;
  idempotencyKey: string;
  legacyUserKey: string | null;
  leaseToken: string;
};

export type VpnSession = AuthenticatedSession &
  (
    | { authKind: "legacy" }
    | { authKind: "account"; accountId: string }
  );

function encryptionKey(): Buffer {
  return decodeSecret(getEnvironment().SESSION_ENCRYPTION_KEY);
}

function grantPurpose(tokenHash: Buffer): string {
  return `session-grant:v1:${tokenHash.toString("hex")}`;
}

function cleanBridgeLabel(value: string, fallback: string): string {
  const cleaned = value
    .normalize("NFC")
    .split("")
    .filter((character) => {
      const codePoint = character.codePointAt(0) ?? 0;
      return codePoint >= 32 && codePoint !== 127;
    })
    .join("")
    .trim()
    .slice(0, 160);
  return cleaned || fallback.slice(0, 160);
}

function sameNullable(left: string | null, right: string | null): boolean {
  return left === right;
}

async function lockActiveAccountSession(
  client: PoolClient,
  session: AuthenticatedAccountSession,
): Promise<ActiveAccountRow> {
  const result = await client.query<ActiveAccountRow>(
    `
      SELECT account.display_name
      FROM account_sessions AS session
      INNER JOIN accounts AS account ON account.account_id = session.account_id
      WHERE session.token_hash = $1
        AND session.account_id = $2
        AND session.revoked_at IS NULL
        AND session.idle_expires_at > now()
        AND session.absolute_expires_at > now()
        AND account.status = 'active'
      FOR UPDATE OF session
    `,
    [session.tokenHash, session.accountId],
  );
  const row = result.rows[0];
  if (!row) {
    throw new AccountApiError("authentication_required", 401);
  }
  return row;
}

async function currentLegacyUserKey(
  client: PoolClient,
  accountId: string,
): Promise<string | null> {
  const result = await client.query<{ user_key: string }>(
    `
      SELECT user_key
      FROM legacy_account_links
      WHERE account_id = $1 AND revoked_at IS NULL
      LIMIT 1
    `,
    [accountId],
  );
  return result.rows[0]?.user_key ?? null;
}

async function authorizationRow(
  client: PoolClient,
  accountId: string,
): Promise<ActiveGrantRow | null> {
  const result = await client.query<ActiveGrantRow>(
    `
      SELECT
        authorization.account_id,
        authorization.bridge_session_token_hash,
        authorization.idempotency_key,
        authorization.requested_legacy_user_key,
        authorization.state,
        authorization.lease_token,
        authorization.lease_expires_at,
        authorization.grant_expires_at,
        principal.bridge_user_key,
        shadow.user_key AS shadow_user_key,
        shadow.user_label,
        shadow.grant_ciphertext,
        shadow.grant_expires_at AS shadow_grant_expires_at,
        shadow.revoked_at AS shadow_revoked_at
      FROM account_bridge_authorizations AS authorization
      LEFT JOIN account_bridge_principals AS principal
        ON principal.account_id = authorization.account_id
      LEFT JOIN web_sessions AS shadow
        ON shadow.token_hash = authorization.bridge_session_token_hash
       AND shadow.session_kind = 'account_bridge'
      WHERE authorization.account_id = $1
      FOR UPDATE OF authorization
    `,
    [accountId],
  );
  return result.rows[0] ?? null;
}

function activeGrantIsUsable(
  row: ActiveGrantRow,
  requestedLegacyUserKey: string | null,
): boolean {
  const now = Date.now();
  return (
    row.state === "active" &&
    sameNullable(row.requested_legacy_user_key, requestedLegacyUserKey) &&
    row.bridge_session_token_hash !== null &&
    row.bridge_user_key !== null &&
    row.shadow_user_key === row.bridge_user_key &&
    row.grant_ciphertext !== null &&
    row.shadow_revoked_at === null &&
    row.grant_expires_at !== null &&
    row.shadow_grant_expires_at !== null &&
    row.grant_expires_at.getTime() > now + REFRESH_SKEW_MS &&
    row.shadow_grant_expires_at.getTime() > now + REFRESH_SKEW_MS
  );
}

function sessionFromActiveGrant(
  accountSession: AuthenticatedAccountSession,
  accountDisplayName: string,
  row: ActiveGrantRow,
): VpnSession {
  if (
    !row.bridge_session_token_hash ||
    !row.bridge_user_key ||
    !row.grant_ciphertext ||
    !row.shadow_grant_expires_at
  ) {
    throw new BridgeError("invalid_account_bridge_session", 503, false);
  }
  let grant: string;
  try {
    grant = decryptString(
      row.grant_ciphertext,
      encryptionKey(),
      grantPurpose(row.bridge_session_token_hash),
    );
  } catch {
    throw new BridgeError("invalid_account_bridge_session", 503, false);
  }
  if (!/^[A-Za-z0-9_-]{32,256}$/.test(grant)) {
    throw new BridgeError("invalid_account_bridge_session", 503, false);
  }
  return {
    authKind: "account",
    accountId: accountSession.accountId,
    publicId: accountSession.publicId,
    rawToken: accountSession.rawToken,
    tokenHash: accountSession.tokenHash,
    userKey: row.bridge_user_key,
    userLabel: cleanBridgeLabel(
      row.user_label ?? accountDisplayName,
      accountDisplayName,
    ),
    deviceLabel: accountSession.deviceName,
    grant,
    grantExpiresAt: row.shadow_grant_expires_at,
    createdAt: accountSession.createdAt,
    lastSeenAt: accountSession.lastSeenAt,
    absoluteExpiresAt: accountSession.absoluteExpiresAt,
  };
}

async function claimAuthorization(
  session: AuthenticatedAccountSession,
  legacyOverride?: string,
): Promise<{ active?: VpnSession; claim?: GrantClaim }> {
  return withTransaction(async (client) => {
    const account = await lockActiveAccountSession(client, session);
    const linkedLegacyUserKey =
      legacyOverride ?? (await currentLegacyUserKey(client, session.accountId));
    const current = await authorizationRow(client, session.accountId);
    if (current && activeGrantIsUsable(current, linkedLegacyUserKey)) {
      return {
        active: sessionFromActiveGrant(
          session,
          account.display_name,
          current,
        ),
      };
    }

    const now = Date.now();
    if (
      current?.state === "pending" &&
      current.lease_token &&
      current.lease_expires_at &&
      current.lease_expires_at.getTime() > now
    ) {
      throw new BridgeError("account_bridge_refresh_in_progress", 503, true);
    }

    const leaseToken = randomUUID();
    const leaseExpiresAt = new Date(now + REFRESH_LEASE_SECONDS * 1_000);
    const retryPending = current?.state === "pending";
    const idempotencyKey = retryPending
      ? current.idempotency_key
      : randomUUID();
    const requestedLegacyUserKey = retryPending
      ? current.requested_legacy_user_key
      : linkedLegacyUserKey;

    if (current) {
      await client.query(
        `
          UPDATE account_bridge_authorizations
          SET
            idempotency_key = $2,
            requested_legacy_user_key = $3,
            state = 'pending',
            lease_token = $4,
            lease_expires_at = $5,
            last_error_code = NULL,
            updated_at = now()
          WHERE account_id = $1
        `,
        [
          session.accountId,
          idempotencyKey,
          requestedLegacyUserKey,
          leaseToken,
          leaseExpiresAt,
        ],
      );
    } else {
      await client.query(
        `
          INSERT INTO account_bridge_authorizations (
            account_id,
            idempotency_key,
            requested_legacy_user_key,
            state,
            lease_token,
            lease_expires_at
          )
          VALUES ($1, $2, $3, 'pending', $4, $5)
        `,
        [
          session.accountId,
          idempotencyKey,
          requestedLegacyUserKey,
          leaseToken,
          leaseExpiresAt,
        ],
      );
    }
    return {
      claim: {
        accountSession: session,
        accountDisplayName: account.display_name,
        idempotencyKey,
        legacyUserKey: requestedLegacyUserKey,
        leaseToken,
      },
    };
  });
}

async function clearFailedLease(claim: GrantClaim, errorCode: string): Promise<void> {
  await query(
    `
      UPDATE account_bridge_authorizations
      SET
        lease_token = NULL,
        lease_expires_at = NULL,
        last_error_code = $4,
        updated_at = now()
      WHERE account_id = $1
        AND idempotency_key = $2
        AND lease_token = $3
        AND state = 'pending'
    `,
    [
      claim.accountSession.accountId,
      claim.idempotencyKey,
      claim.leaseToken,
      errorCode.slice(0, 80),
    ],
  );
}

async function rotateExpiredClaim(claim: GrantClaim): Promise<GrantClaim> {
  return withTransaction(async (client) => {
    await lockActiveAccountSession(client, claim.accountSession);
    const nextIdempotencyKey = randomUUID();
    const nextLeaseToken = randomUUID();
    const result = await client.query(
      `
        UPDATE account_bridge_authorizations
        SET
          idempotency_key = $4,
          lease_token = $5,
          lease_expires_at = now() + ($6 * interval '1 second'),
          last_error_code = NULL,
          updated_at = now()
        WHERE account_id = $1
          AND idempotency_key = $2
          AND lease_token = $3
          AND state = 'pending'
      `,
      [
        claim.accountSession.accountId,
        claim.idempotencyKey,
        claim.leaseToken,
        nextIdempotencyKey,
        nextLeaseToken,
        REFRESH_LEASE_SECONDS,
      ],
    );
    if (result.rowCount !== 1) {
      throw new BridgeError("account_bridge_refresh_raced", 503, true);
    }
    return {
      ...claim,
      idempotencyKey: nextIdempotencyKey,
      leaseToken: nextLeaseToken,
    };
  });
}

async function queueShadowRevocation(
  client: PoolClient,
  tokenHash: Buffer,
): Promise<void> {
  await client.query(
    `
      UPDATE web_sessions
      SET revoked_at = COALESCE(revoked_at, now())
      WHERE token_hash = $1 AND session_kind = 'account_bridge'
    `,
    [tokenHash],
  );
  await client.query(
    `
      INSERT INTO web_grant_revocations (session_token_hash, idempotency_key)
      VALUES ($1, $2)
      ON CONFLICT (session_token_hash) DO NOTHING
    `,
    [tokenHash, randomUUID()],
  );
}

async function queueUnadoptedGrant(
  accountId: string,
  authorization: AccountBridgeAuthorization,
): Promise<void> {
  const tokenHash = randomBytes(32);
  const now = new Date();
  const expiresAt = new Date(
    now.getTime() + authorization.grantExpiresIn * 1_000,
  );
  await withTransaction(async (client) => {
    await client.query(
      `
        INSERT INTO web_sessions (
          token_hash,
          public_id,
          user_key,
          user_label,
          device_label,
          grant_ciphertext,
          grant_expires_at,
          created_at,
          last_seen_at,
          idle_expires_at,
          absolute_expires_at,
          revoked_at,
          account_id,
          session_kind
        )
        VALUES ($1, $2, $3, $4, 'Account bridge cleanup', $5, $6, $7, $7, $6, $6, $7, $8, 'account_bridge')
      `,
      [
        tokenHash,
        randomUUID(),
        authorization.user.userKey,
        cleanBridgeLabel(authorization.user.userLabel, "Levik Account"),
        encryptString(
          authorization.grant,
          encryptionKey(),
          grantPurpose(tokenHash),
        ),
        expiresAt,
        now,
        accountId,
      ],
    );
    await queueShadowRevocation(client, tokenHash);
  });
}

async function persistAuthorization(
  claim: GrantClaim,
  authorization: AccountBridgeAuthorization,
): Promise<VpnSession> {
  const shadowTokenHash = randomBytes(32);
  const now = new Date();
  const grantExpiresAt = new Date(
    now.getTime() + authorization.grantExpiresIn * 1_000 - 30_000,
  );
  try {
    return await withTransaction(async (client) => {
      await lockActiveAccountSession(client, claim.accountSession);
      const current = await authorizationRow(
        client,
        claim.accountSession.accountId,
      );
      if (
        !current ||
        current.state !== "pending" ||
        current.idempotency_key !== claim.idempotencyKey ||
        current.lease_token !== claim.leaseToken ||
        !sameNullable(
          current.requested_legacy_user_key,
          claim.legacyUserKey,
        )
      ) {
        throw new BridgeError("account_bridge_refresh_raced", 503, true);
      }

      const principal = await client.query<{ bridge_user_key: string }>(
        `
          SELECT bridge_user_key
          FROM account_bridge_principals
          WHERE account_id = $1
          FOR UPDATE
        `,
        [claim.accountSession.accountId],
      );
      const previousUserKey = principal.rows[0]?.bridge_user_key;
      if (!previousUserKey) {
        if (
          claim.legacyUserKey !== null &&
          authorization.user.userKey !== claim.legacyUserKey
        ) {
          throw new AccountApiError("credential_conflict", 409);
        }
        await client.query(
          `
            INSERT INTO account_bridge_principals (account_id, bridge_user_key)
            VALUES ($1, $2)
          `,
          [claim.accountSession.accountId, authorization.user.userKey],
        );
      } else if (previousUserKey !== authorization.user.userKey) {
        if (claim.legacyUserKey !== authorization.user.userKey) {
          throw new AccountApiError("credential_conflict", 409);
        }
        await client.query(
          `
            UPDATE account_bridge_principals
            SET bridge_user_key = $2, updated_at = now()
            WHERE account_id = $1 AND bridge_user_key = $3
          `,
          [
            claim.accountSession.accountId,
            authorization.user.userKey,
            previousUserKey,
          ],
        );
      }

      await client.query(
        `
          INSERT INTO web_sessions (
            token_hash,
            public_id,
            user_key,
            user_label,
            device_label,
            grant_ciphertext,
            grant_expires_at,
            created_at,
            last_seen_at,
            idle_expires_at,
            absolute_expires_at,
            account_id,
            session_kind
          )
          VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $8, $7, $7, $9, 'account_bridge')
        `,
        [
          shadowTokenHash,
          randomUUID(),
          authorization.user.userKey,
          cleanBridgeLabel(
            authorization.user.userLabel,
            claim.accountDisplayName,
          ),
          claim.accountSession.deviceName,
          encryptString(
            authorization.grant,
            encryptionKey(),
            grantPurpose(shadowTokenHash),
          ),
          grantExpiresAt,
          now,
          claim.accountSession.accountId,
        ],
      );

      const updated = await client.query(
        `
          UPDATE account_bridge_authorizations
          SET
            bridge_session_token_hash = $4,
            state = 'active',
            lease_token = NULL,
            lease_expires_at = NULL,
            grant_expires_at = $5,
            last_error_code = NULL,
            updated_at = now()
          WHERE account_id = $1
            AND idempotency_key = $2
            AND lease_token = $3
            AND state = 'pending'
        `,
        [
          claim.accountSession.accountId,
          claim.idempotencyKey,
          claim.leaseToken,
          shadowTokenHash,
          grantExpiresAt,
        ],
      );
      if (updated.rowCount !== 1) {
        throw new BridgeError("account_bridge_refresh_raced", 503, true);
      }
      if (current.bridge_session_token_hash) {
        await queueShadowRevocation(
          client,
          current.bridge_session_token_hash,
        );
      }

      return {
        authKind: "account",
        accountId: claim.accountSession.accountId,
        publicId: claim.accountSession.publicId,
        rawToken: claim.accountSession.rawToken,
        tokenHash: claim.accountSession.tokenHash,
        userKey: authorization.user.userKey,
        userLabel: cleanBridgeLabel(
          authorization.user.userLabel,
          claim.accountDisplayName,
        ),
        deviceLabel: claim.accountSession.deviceName,
        grant: authorization.grant,
        grantExpiresAt,
        createdAt: claim.accountSession.createdAt,
        lastSeenAt: claim.accountSession.lastSeenAt,
        absoluteExpiresAt: claim.accountSession.absoluteExpiresAt,
      };
    });
  } catch (error) {
    const reconciliation = await reconcilePersistenceFailure(
      claim,
      authorization,
    );
    if (reconciliation.adopted) return reconciliation.adopted;
    if (reconciliation.shouldRevoke) {
      await queueUnadoptedGrant(
        claim.accountSession.accountId,
        authorization,
      ).catch(async () => {
        await revokeBridgeGrant(authorization.grant, randomUUID()).catch(() => {});
      });
    }
    throw error;
  }
}

async function reconcilePersistenceFailure(
  claim: GrantClaim,
  authorization: AccountBridgeAuthorization,
): Promise<{ adopted?: VpnSession; shouldRevoke: boolean }> {
  try {
    return await withTransaction(async (client) => {
      const account = await lockActiveAccountSession(client, claim.accountSession);
      const current = await authorizationRow(client, claim.accountSession.accountId);
      if (
        current &&
        current.idempotency_key === claim.idempotencyKey &&
        activeGrantIsUsable(current, claim.legacyUserKey)
      ) {
        const adopted = sessionFromActiveGrant(
          claim.accountSession,
          account.display_name,
          current,
        );
        if (constantTimeEqual(adopted.grant, authorization.grant)) {
          return { adopted, shouldRevoke: false };
        }
      }
      if (
        current?.state === "pending" &&
        current.idempotency_key === claim.idempotencyKey &&
        current.lease_token !== claim.leaseToken
      ) {
        // Another lease is replaying the same persisted idempotent request.
        // It will adopt or revoke the shared response; revoking here could kill
        // the winner's grant.
        return { shouldRevoke: false };
      }
      if (
        current?.state === "pending" &&
        current.idempotency_key === claim.idempotencyKey &&
        current.lease_token === claim.leaseToken
      ) {
        if (current.bridge_session_token_hash) {
          await queueShadowRevocation(
            client,
            current.bridge_session_token_hash,
          );
        }
        await client.query(
          `
            UPDATE account_bridge_authorizations
            SET
              state = 'revoked',
              bridge_session_token_hash = NULL,
              requested_legacy_user_key = NULL,
              lease_token = NULL,
              lease_expires_at = NULL,
              last_error_code = 'grant_persist_failed',
              updated_at = now()
            WHERE account_id = $1
              AND idempotency_key = $2
              AND lease_token = $3
              AND state = 'pending'
          `,
          [
            claim.accountSession.accountId,
            claim.idempotencyKey,
            claim.leaseToken,
          ],
        );
      }
      return { shouldRevoke: true };
    });
  } catch {
    return { shouldRevoke: true };
  }
}

async function refreshAuthorization(claim: GrantClaim): Promise<VpnSession> {
  let currentClaim = claim;
  let authorization: AccountBridgeAuthorization;
  try {
    authorization = await issueAccountBridgeGrant({
      accountId: currentClaim.accountSession.accountId,
      legacyUserKey: currentClaim.legacyUserKey,
      idempotencyKey: currentClaim.idempotencyKey,
    });
  } catch (error) {
    if (error instanceof BridgeError && error.code === "idempotency_expired") {
      currentClaim = await rotateExpiredClaim(currentClaim);
      try {
        authorization = await issueAccountBridgeGrant({
          accountId: currentClaim.accountSession.accountId,
          legacyUserKey: currentClaim.legacyUserKey,
          idempotencyKey: currentClaim.idempotencyKey,
        });
      } catch (retryError) {
        await clearFailedLease(
          currentClaim,
          retryError instanceof BridgeError
            ? retryError.code
            : "account_bridge_unavailable",
        ).catch(() => {});
        throw retryError;
      }
    } else {
      await clearFailedLease(
        currentClaim,
        error instanceof BridgeError ? error.code : "account_bridge_unavailable",
      ).catch(() => {});
      throw error;
    }
  }
  return persistAuthorization(currentClaim, authorization);
}

export async function ensureAccountBridgeSession(
  session: AuthenticatedAccountSession,
  options: { legacyUserKey?: string } = {},
): Promise<VpnSession> {
  let claimed: Awaited<ReturnType<typeof claimAuthorization>>;
  try {
    claimed = await claimAuthorization(session, options.legacyUserKey);
  } catch (error) {
    if (isPostgresError(error, "23505")) {
      throw new AccountApiError("credential_conflict", 409);
    }
    throw error;
  }
  if (claimed.active) return claimed.active;
  if (!claimed.claim) {
    throw new BridgeError("account_bridge_unavailable", 503, true);
  }
  return refreshAuthorization(claimed.claim);
}

const optionalVpnSession = cache(async (): Promise<VpnSession | null> => {
  const cookieStore = await cookies();
  const accountToken = cookieStore.get(ACCOUNT_SESSION_COOKIE_NAME)?.value;
  if (accountToken) {
    const accountSession = await getAccountSessionByToken(accountToken);
    if (accountSession) {
      return ensureAccountBridgeSession(accountSession);
    }
  }
  const legacySession = await getOptionalSession();
  return legacySession ? { ...legacySession, authKind: "legacy" } : null;
});

export async function getOptionalVpnSession(): Promise<VpnSession | null> {
  return optionalVpnSession();
}

export async function requireVpnSession(): Promise<VpnSession> {
  const session = await getOptionalVpnSession();
  if (!session) redirect("/login");
  return session;
}
