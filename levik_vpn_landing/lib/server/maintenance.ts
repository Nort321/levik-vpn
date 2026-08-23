import "server-only";

import { randomUUID } from "node:crypto";

import { revokeBridgeGrant } from "@/lib/server/bridge/auth";
import { BridgeError } from "@/lib/server/bridge/core";
import {
  constantTimeEqual,
  decodeSecret,
  decryptString,
} from "@/lib/server/crypto";
import { query, withTransaction } from "@/lib/server/db";
import { getEnvironment } from "@/lib/server/env";

const REVOCATION_BATCH_SIZE = 10;
const EXPIRED_SESSION_BATCH_SIZE = 100;
const CLEANUP_BATCH_SIZE = 250;
const RETENTION_INTERVAL = "24 hours";

type GrantRevocationClaimRow = {
  id: string;
  session_token_hash: Buffer;
  idempotency_key: string;
  grant_ciphertext: string;
  grant_expires_at: Date;
};

export type GrantRevocationClaim = {
  id: string;
  leaseToken: string;
  sessionTokenHash: Buffer;
  idempotencyKey: string;
  grantCiphertext: string;
  grantExpiresAt: Date;
};

type RevocationBatchDependencies = {
  claim: (limit: number) => Promise<GrantRevocationClaim[]>;
  decryptGrant: (claim: GrantRevocationClaim) => string;
  revokeGrant: (grant: string, idempotencyKey: string) => Promise<void>;
  complete: (claim: GrantRevocationClaim) => Promise<boolean>;
  fail: (
    claim: GrantRevocationClaim,
    errorCode: string,
  ) => Promise<boolean>;
  now: () => Date;
};

export type RevocationBatchSummary = {
  claimed: number;
  completed: number;
  failed: number;
  leaseLost: number;
};

export type MaintenanceSummary = {
  revocations: RevocationBatchSummary;
  expiredSessionsRevoked: number;
  expiredSessionsQueued: number;
  cleaned: {
    loginAttempts: number;
    rateLimits: number;
    ephemeralCredentials: number;
    sessions: number;
    grantRevocations: number;
    rdapCache: number;
    encryptedNotes: number;
    browserChecks: number;
  };
};

export function isMaintenanceRequestAuthorized(
  authorization: string | null,
  expectedToken: string,
): boolean {
  const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(authorization ?? "");
  return Boolean(match?.[1] && constantTimeEqual(match[1], expectedToken));
}

function decryptClaimGrant(claim: GrantRevocationClaim): string {
  if (claim.sessionTokenHash.byteLength !== 32) {
    throw new Error("Stored session token hash is invalid");
  }
  return decryptString(
    claim.grantCiphertext,
    decodeSecret(getEnvironment().SESSION_ENCRYPTION_KEY),
    `session-grant:v1:${claim.sessionTokenHash.toString("hex")}`,
  );
}

async function claimGrantRevocations(
  limit: number,
): Promise<GrantRevocationClaim[]> {
  const leaseToken = randomUUID();
  const result = await withTransaction((client) =>
    client.query<GrantRevocationClaimRow>(
      `
        WITH candidates AS (
          SELECT id
          FROM web_grant_revocations
          WHERE completed_at IS NULL
            AND next_attempt_at <= now()
            AND (
              lease_expires_at IS NULL
              OR lease_expires_at <= now()
            )
          ORDER BY next_attempt_at ASC, id ASC
          FOR UPDATE SKIP LOCKED
          LIMIT $1
        ),
        claimed AS (
          UPDATE web_grant_revocations AS revocation
          SET
            lease_token = $2,
            lease_expires_at = now() + interval '90 seconds'
          FROM candidates
          WHERE revocation.id = candidates.id
          RETURNING
            revocation.id,
            revocation.session_token_hash,
            revocation.idempotency_key
        )
        SELECT
          claimed.id,
          claimed.session_token_hash,
          claimed.idempotency_key,
          session.grant_ciphertext,
          session.grant_expires_at
        FROM claimed
        INNER JOIN web_sessions AS session
          ON session.token_hash = claimed.session_token_hash
        ORDER BY claimed.id ASC
      `,
      [limit, leaseToken],
    ),
  );

  return result.rows.map((row) => ({
    id: row.id,
    leaseToken,
    sessionTokenHash: row.session_token_hash,
    idempotencyKey: row.idempotency_key,
    grantCiphertext: row.grant_ciphertext,
    grantExpiresAt: row.grant_expires_at,
  }));
}

async function completeGrantRevocation(
  claim: GrantRevocationClaim,
): Promise<boolean> {
  const result = await query(
    `
      UPDATE web_grant_revocations
      SET
        completed_at = now(),
        last_error_code = NULL,
        lease_token = NULL,
        lease_expires_at = NULL
      WHERE id = $1
        AND lease_token = $2
        AND completed_at IS NULL
    `,
    [claim.id, claim.leaseToken],
  );
  return result.rowCount === 1;
}

async function failGrantRevocation(
  claim: GrantRevocationClaim,
  errorCode: string,
): Promise<boolean> {
  const result = await query(
    `
      UPDATE web_grant_revocations
      SET
        attempts = attempts + 1,
        next_attempt_at = now() + (
          LEAST(3600, (30 * power(2, LEAST(attempts, 7))))
          * interval '1 second'
        ),
        last_error_code = $3,
        lease_token = NULL,
        lease_expires_at = NULL
      WHERE id = $1
        AND lease_token = $2
        AND completed_at IS NULL
    `,
    [claim.id, claim.leaseToken, errorCode.slice(0, 80)],
  );
  return result.rowCount === 1;
}

function revocationErrorCode(error: unknown): string {
  if (error instanceof BridgeError) {
    return error.code;
  }
  if (error instanceof Error && error.message.startsWith("Stored session")) {
    return "invalid_stored_session";
  }
  return "maintenance_revocation_failed";
}

const productionRevocationDependencies: RevocationBatchDependencies = {
  claim: claimGrantRevocations,
  decryptGrant: decryptClaimGrant,
  revokeGrant: revokeBridgeGrant,
  complete: completeGrantRevocation,
  fail: failGrantRevocation,
  now: () => new Date(),
};

export async function retryPendingGrantRevocations(
  dependencies: RevocationBatchDependencies = productionRevocationDependencies,
): Promise<RevocationBatchSummary> {
  const claims = await dependencies.claim(REVOCATION_BATCH_SIZE);
  const outcomes = await Promise.all(
    claims.map(async (claim) => {
      try {
        if (claim.grantExpiresAt.getTime() > dependencies.now().getTime()) {
          const grant = dependencies.decryptGrant(claim);
          await dependencies.revokeGrant(grant, claim.idempotencyKey);
        }
        return (await dependencies.complete(claim))
          ? "completed"
          : "lease_lost";
      } catch (error) {
        try {
          return (await dependencies.fail(
            claim,
            revocationErrorCode(error),
          ))
            ? "failed"
            : "lease_lost";
        } catch {
          return "lease_lost";
        }
      }
    }),
  );

  return {
    claimed: claims.length,
    completed: outcomes.filter((outcome) => outcome === "completed").length,
    failed: outcomes.filter((outcome) => outcome === "failed").length,
    leaseLost: outcomes.filter((outcome) => outcome === "lease_lost").length,
  };
}

async function revokeExpiredSessions(): Promise<{
  revoked: number;
  queued: number;
}> {
  const result = await withTransaction((client) =>
    client.query<{ revoked_count: number; queued_count: number }>(
      `
        WITH expired AS (
          SELECT token_hash
          FROM web_sessions
          WHERE revoked_at IS NULL
            AND (
              idle_expires_at <= now()
              OR absolute_expires_at <= now()
              OR grant_expires_at <= now()
            )
          ORDER BY
            LEAST(idle_expires_at, absolute_expires_at, grant_expires_at)
          FOR UPDATE SKIP LOCKED
          LIMIT $1
        ),
        revoked AS (
          UPDATE web_sessions AS session
          SET revoked_at = now()
          FROM expired
          WHERE session.token_hash = expired.token_hash
          RETURNING session.token_hash, session.grant_expires_at
        ),
        queued AS (
          INSERT INTO web_grant_revocations (
            session_token_hash,
            idempotency_key
          )
          SELECT token_hash, gen_random_uuid()
          FROM revoked
          WHERE grant_expires_at > now()
          ON CONFLICT (session_token_hash) DO NOTHING
          RETURNING id
        ),
        invalidated_authorizations AS (
          UPDATE account_bridge_authorizations AS authorization
          SET
            state = 'revoked',
            bridge_session_token_hash = NULL,
            requested_legacy_user_key = NULL,
            lease_token = NULL,
            lease_expires_at = NULL,
            updated_at = now()
          FROM revoked
          WHERE authorization.bridge_session_token_hash = revoked.token_hash
          RETURNING authorization.account_id
        )
        SELECT
          (SELECT count(*)::int FROM revoked) AS revoked_count,
          (SELECT count(*)::int FROM queued) AS queued_count,
          (SELECT count(*)::int FROM invalidated_authorizations)
            AS invalidated_authorizations_count
      `,
      [EXPIRED_SESSION_BATCH_SIZE],
    ),
  );
  const row = result.rows[0];
  return {
    revoked: row?.revoked_count ?? 0,
    queued: row?.queued_count ?? 0,
  };
}

async function cleanupExpiredData(): Promise<MaintenanceSummary["cleaned"]> {
  return withTransaction(async (client) => {
    const loginAttempts = await client.query(
      `
        WITH candidates AS (
          SELECT token_hash
          FROM web_login_attempts
          WHERE expires_at <= now()
          ORDER BY expires_at ASC
          FOR UPDATE SKIP LOCKED
          LIMIT $1
        )
        DELETE FROM web_login_attempts AS attempt
        USING candidates
        WHERE attempt.token_hash = candidates.token_hash
      `,
      [CLEANUP_BATCH_SIZE],
    );
    const rateLimits = await client.query(
      `
        WITH candidates AS (
          SELECT key_hash, bucket_start
          FROM web_rate_limits
          WHERE expires_at <= now()
          ORDER BY expires_at ASC
          FOR UPDATE SKIP LOCKED
          LIMIT $1
        )
        DELETE FROM web_rate_limits AS rate_limit
        USING candidates
        WHERE rate_limit.key_hash = candidates.key_hash
          AND rate_limit.bucket_start = candidates.bucket_start
      `,
      [CLEANUP_BATCH_SIZE],
    );
    const ephemeralCredentials = await client.query(
      `
        WITH candidates AS (
          SELECT user_key, credential_kind
          FROM web_ephemeral_credentials
          WHERE expires_at <= now()
          ORDER BY expires_at ASC
          FOR UPDATE SKIP LOCKED
          LIMIT $1
        )
        DELETE FROM web_ephemeral_credentials AS credential
        USING candidates
        WHERE credential.user_key = candidates.user_key
          AND credential.credential_kind = candidates.credential_kind
      `,
      [CLEANUP_BATCH_SIZE],
    );
    const completedRevocations = await client.query<{
      deleted_sessions: number;
      deleted_revocations: number;
    }>(
      `
        WITH candidates AS (
          SELECT revocation.id, revocation.session_token_hash
          FROM web_grant_revocations AS revocation
          INNER JOIN web_sessions AS session
            ON session.token_hash = revocation.session_token_hash
          WHERE revocation.completed_at <=
              now() - $2::interval
            AND session.revoked_at IS NOT NULL
          ORDER BY revocation.completed_at ASC
          FOR UPDATE OF revocation, session SKIP LOCKED
          LIMIT $1
        ),
        deleted_revocations AS (
          DELETE FROM web_grant_revocations AS revocation
          USING candidates
          WHERE revocation.id = candidates.id
          RETURNING revocation.session_token_hash
        ),
        invalidated_authorizations AS (
          UPDATE account_bridge_authorizations AS authorization
          SET
            state = 'revoked',
            bridge_session_token_hash = NULL,
            requested_legacy_user_key = NULL,
            lease_token = NULL,
            lease_expires_at = NULL,
            updated_at = now()
          FROM deleted_revocations
          WHERE authorization.bridge_session_token_hash =
              deleted_revocations.session_token_hash
          RETURNING deleted_revocations.session_token_hash
        ),
        sessions_ready_for_deletion AS (
          SELECT deleted_revocations.session_token_hash
          FROM deleted_revocations
          LEFT JOIN invalidated_authorizations
            ON invalidated_authorizations.session_token_hash =
              deleted_revocations.session_token_hash
        ),
        deleted_sessions AS (
          DELETE FROM web_sessions AS session
          USING sessions_ready_for_deletion
          WHERE session.token_hash =
              sessions_ready_for_deletion.session_token_hash
          RETURNING session.token_hash
        )
        SELECT
          (SELECT count(*)::int FROM deleted_sessions)
            AS deleted_sessions,
          (SELECT count(*)::int FROM deleted_revocations)
            AS deleted_revocations
      `,
      [CLEANUP_BATCH_SIZE, RETENTION_INTERVAL],
    );
    const expiredSessions = await client.query(
      `
        WITH candidates AS (
          SELECT session.token_hash
          FROM web_sessions AS session
          WHERE session.grant_expires_at <=
              now() - $2::interval
            AND (
              session.revoked_at IS NOT NULL
              OR session.idle_expires_at <= now()
              OR session.absolute_expires_at <= now()
            )
            AND NOT EXISTS (
              SELECT 1
              FROM web_grant_revocations AS revocation
              WHERE revocation.session_token_hash = session.token_hash
            )
          ORDER BY session.grant_expires_at ASC
          FOR UPDATE OF session SKIP LOCKED
          LIMIT $1
        ),
        invalidated_authorizations AS (
          UPDATE account_bridge_authorizations AS authorization
          SET
            state = 'revoked',
            bridge_session_token_hash = NULL,
            requested_legacy_user_key = NULL,
            lease_token = NULL,
            lease_expires_at = NULL,
            updated_at = now()
          FROM candidates
          WHERE authorization.bridge_session_token_hash =
              candidates.token_hash
          RETURNING candidates.token_hash
        ),
        sessions_ready_for_deletion AS (
          SELECT candidates.token_hash
          FROM candidates
          LEFT JOIN invalidated_authorizations
            ON invalidated_authorizations.token_hash = candidates.token_hash
        )
        DELETE FROM web_sessions AS session
        USING sessions_ready_for_deletion
        WHERE session.token_hash = sessions_ready_for_deletion.token_hash
      `,
      [CLEANUP_BATCH_SIZE, RETENTION_INTERVAL],
    );
    const rdapCache = await client.query(
      `
        WITH candidates AS (
          SELECT network
          FROM ip_rdap_cache
          WHERE expires_at <= now() - interval '7 days'
          ORDER BY expires_at ASC
          FOR UPDATE SKIP LOCKED
          LIMIT $1
        )
        DELETE FROM ip_rdap_cache AS cache
        USING candidates
        WHERE cache.network = candidates.network
      `,
      [CLEANUP_BATCH_SIZE],
    );
    const encryptedNotes = await client.query(
      `
        WITH candidates AS (
          SELECT id
          FROM encrypted_notes
          WHERE expires_at <= now()
          ORDER BY expires_at ASC
          FOR UPDATE SKIP LOCKED
          LIMIT $1
        )
        DELETE FROM encrypted_notes AS note
        USING candidates
        WHERE note.id = candidates.id
      `,
      [CLEANUP_BATCH_SIZE],
    );
    const browserChecks = await client.query(
      `
        WITH candidates AS (
          SELECT id
          FROM monitor_browser_checks
          WHERE received_at <= now() - interval '7 days'
          ORDER BY received_at ASC
          FOR UPDATE SKIP LOCKED
          LIMIT $1
        )
        DELETE FROM monitor_browser_checks AS browser_check
        USING candidates
        WHERE browser_check.id = candidates.id
      `,
      [CLEANUP_BATCH_SIZE],
    );

    const paired = completedRevocations.rows[0];
    return {
      loginAttempts: loginAttempts.rowCount ?? 0,
      rateLimits: rateLimits.rowCount ?? 0,
      ephemeralCredentials: ephemeralCredentials.rowCount ?? 0,
      sessions:
        (paired?.deleted_sessions ?? 0) + (expiredSessions.rowCount ?? 0),
      grantRevocations: paired?.deleted_revocations ?? 0,
      rdapCache: rdapCache.rowCount ?? 0,
      encryptedNotes: encryptedNotes.rowCount ?? 0,
      browserChecks: browserChecks.rowCount ?? 0,
    };
  });
}

export async function runMaintenance(): Promise<MaintenanceSummary> {
  const expiredSessions = await revokeExpiredSessions();
  const revocations = await retryPendingGrantRevocations();
  const cleaned = await cleanupExpiredData();
  return {
    revocations,
    expiredSessionsRevoked: expiredSessions.revoked,
    expiredSessionsQueued: expiredSessions.queued,
    cleaned,
  };
}
