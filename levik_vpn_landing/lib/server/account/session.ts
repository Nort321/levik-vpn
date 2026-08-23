import "server-only";

import { randomUUID } from "node:crypto";

import type { NextRequest, NextResponse } from "next/server";
import type { PoolClient } from "pg";

import type { AuthenticatedSession } from "@/lib/server/browser-auth";
import { AccountApiError } from "@/lib/server/account/errors";
import { cleanDisplayText } from "@/lib/server/account/identifiers";
import type { AccountStatus } from "@/lib/server/account/model";
import { decodeSecret, hmacBase64Url, randomToken, sha256 } from "@/lib/server/crypto";
import { query, withTransaction } from "@/lib/server/db";
import { getEnvironment } from "@/lib/server/env";
import { csrfTokenForSession } from "@/lib/server/security";

export const ACCOUNT_SESSION_COOKIE_NAME = "__Host-levik_account";
export const ACCOUNT_AUTH_CHALLENGE_COOKIE_NAME = "__Host-levik_account_auth";

const SESSION_IDLE_SECONDS = 24 * 60 * 60;
const SESSION_ABSOLUTE_SECONDS = 30 * 24 * 60 * 60;

export const accountSessionCookieOptions = {
  httpOnly: true,
  secure: true,
  sameSite: "lax" as const,
  path: "/",
  maxAge: SESSION_ABSOLUTE_SECONDS,
};

export const accountAuthChallengeCookieOptions = {
  httpOnly: true,
  secure: true,
  sameSite: "lax" as const,
  // The __Host- cookie prefix requires Path=/ and forbids Domain.
  path: "/",
  maxAge: 5 * 60,
};

export type AccountAuthMethod =
  | "google"
  | "passkey"
  | "password"
  | "recovery"
  | "telegram";

type AccountSessionRow = {
  token_hash: Buffer;
  public_id: string;
  account_id: string;
  auth_method: AccountAuthMethod;
  device_name: string;
  authenticated_at: Date;
  created_at: Date;
  last_seen_at: Date;
  idle_expires_at: Date;
  absolute_expires_at: Date;
  revoked_at: Date | null;
  account_status: AccountStatus;
};

export type AuthenticatedAccountSession = {
  rawToken: string;
  tokenHash: Buffer;
  publicId: string;
  accountId: string;
  authMethod: AccountAuthMethod;
  deviceName: string;
  authenticatedAt: Date;
  createdAt: Date;
  lastSeenAt: Date;
  absoluteExpiresAt: Date;
};

export type NewAccountSession = AuthenticatedAccountSession;

export type AccountSessionContext = {
  deviceName?: string;
  userAgent: string | null;
  clientAddress: string | null;
};

function coarseDeviceName(userAgent: string | null): string {
  const source = (userAgent ?? "").slice(0, 512);
  const client = /Android/.test(source)
    ? "Levik VPN · Android"
    : /iPhone|iPad/.test(source)
      ? "Browser · iOS"
      : /Windows/.test(source)
        ? "Browser · Windows"
        : /Macintosh|Mac OS X/.test(source)
          ? "Browser · macOS"
          : /Linux/.test(source)
            ? "Browser · Linux"
            : "Browser";
  return client;
}

function addressKey(address: string | null): string | null {
  if (!address) {
    return null;
  }
  return `ip_${hmacBase64Url(
    decodeSecret(getEnvironment().AUDIT_HMAC_KEY),
    `account-client-address:v1:${address}`,
  ).slice(0, 32)}`;
}

function fromRow(row: AccountSessionRow, rawToken: string): AuthenticatedAccountSession {
  return {
    rawToken,
    tokenHash: row.token_hash,
    publicId: row.public_id,
    accountId: row.account_id,
    authMethod: row.auth_method,
    deviceName: row.device_name,
    authenticatedAt: row.authenticated_at,
    createdAt: row.created_at,
    lastSeenAt: row.last_seen_at,
    absoluteExpiresAt: row.absolute_expires_at,
  };
}

export async function createAccountSession(
  accountId: string,
  authMethod: AccountAuthMethod,
  context: AccountSessionContext,
): Promise<NewAccountSession> {
  return withTransaction((client) =>
    createAccountSessionWithClient(client, accountId, authMethod, context),
  );
}

export async function createAccountSessionWithClient(
  client: PoolClient,
  accountId: string,
  authMethod: AccountAuthMethod,
  context: AccountSessionContext,
): Promise<NewAccountSession> {
  const rawToken = randomToken();
  const tokenHash = sha256(rawToken);
  const now = new Date();
  const absoluteExpiresAt = new Date(now.getTime() + SESSION_ABSOLUTE_SECONDS * 1_000);
  const idleExpiresAt = new Date(now.getTime() + SESSION_IDLE_SECONDS * 1_000);
  const publicId = randomUUID();
  const deviceName = cleanDisplayText(
    context.deviceName ?? coarseDeviceName(context.userAgent),
    120,
    "Browser",
  );

  const inserted = await client.query(
    `
      INSERT INTO account_sessions (
        token_hash,
        public_id,
        account_id,
        auth_method,
        device_name,
        last_ip_key,
        authenticated_at,
        created_at,
        last_seen_at,
        idle_expires_at,
        absolute_expires_at
      )
      SELECT $1, $2, account_id, $3, $4, $5, $6, $6, $6, $7, $8
      FROM accounts
      WHERE account_id = $9 AND status IN ('active', 'deletion_pending')
    `,
    [
      tokenHash,
      publicId,
      authMethod,
      deviceName,
      addressKey(context.clientAddress),
      now,
      idleExpiresAt,
      absoluteExpiresAt,
      accountId,
    ],
  );
  if (inserted.rowCount !== 1) {
    throw new AccountApiError("account_not_found", 404);
  }

  return {
    rawToken,
    tokenHash,
    publicId,
    accountId,
    authMethod,
    deviceName,
    authenticatedAt: now,
    createdAt: now,
    lastSeenAt: now,
    absoluteExpiresAt,
  };
}

export async function linkLegacyAccountSession(
  accountId: string,
  legacySession: AuthenticatedSession,
): Promise<AuthenticatedAccountSession> {
  const now = new Date();
  await withTransaction(async (client) => {
    const linkedWebSession = await client.query(
      `
        UPDATE web_sessions
        SET account_id = $2
        WHERE token_hash = $1
          AND (account_id IS NULL OR account_id = $2)
      `,
      [legacySession.tokenHash, accountId],
    );
    if (linkedWebSession.rowCount !== 1) {
      throw new AccountApiError("credential_conflict", 409);
    }
    const linkedAccountSession = await client.query(
      `
        INSERT INTO account_sessions (
          token_hash,
          public_id,
          account_id,
          legacy_session_token_hash,
          auth_method,
          device_name,
          authenticated_at,
          created_at,
          last_seen_at,
          idle_expires_at,
          absolute_expires_at
        )
        VALUES ($1, $2, $3, $1, 'telegram', $4, $5, $6, $7, $8, $8)
        ON CONFLICT (token_hash)
        DO UPDATE SET
          account_id = EXCLUDED.account_id,
          legacy_session_token_hash = EXCLUDED.legacy_session_token_hash,
          device_name = EXCLUDED.device_name,
          last_seen_at = EXCLUDED.last_seen_at,
          idle_expires_at = EXCLUDED.idle_expires_at,
          absolute_expires_at = EXCLUDED.absolute_expires_at,
          revoked_at = NULL
        WHERE account_sessions.account_id = EXCLUDED.account_id
      `,
      [
        legacySession.tokenHash,
        legacySession.publicId,
        accountId,
        legacySession.deviceLabel,
        now,
        legacySession.createdAt,
        legacySession.lastSeenAt,
        legacySession.absoluteExpiresAt,
      ],
    );
    if (linkedAccountSession.rowCount !== 1) {
      throw new AccountApiError("credential_conflict", 409);
    }
  });
  return {
    rawToken: legacySession.rawToken,
    tokenHash: legacySession.tokenHash,
    publicId: legacySession.publicId,
    accountId,
    authMethod: "telegram",
    deviceName: legacySession.deviceLabel,
    authenticatedAt: now,
    createdAt: legacySession.createdAt,
    lastSeenAt: legacySession.lastSeenAt,
    absoluteExpiresAt: legacySession.absoluteExpiresAt,
  };
}

export async function getAccountSession(
  request: NextRequest,
  touch = true,
): Promise<AuthenticatedAccountSession | null> {
  const rawToken = request.cookies.get(ACCOUNT_SESSION_COOKIE_NAME)?.value;
  return rawToken ? getAccountSessionByToken(rawToken, touch) : null;
}

export async function getAccountSessionByToken(
  rawToken: string,
  touch = true,
): Promise<AuthenticatedAccountSession | null> {
  if (!rawToken || !/^[A-Za-z0-9_-]{43}$/.test(rawToken)) {
    return null;
  }
  const tokenHash = sha256(rawToken);
  const result = await query<AccountSessionRow>(
    `
      SELECT
        session.token_hash,
        session.public_id,
        session.account_id,
        session.auth_method,
        session.device_name,
        session.authenticated_at,
        session.created_at,
        session.last_seen_at,
        session.idle_expires_at,
        session.absolute_expires_at,
        session.revoked_at,
        account.status AS account_status
      FROM account_sessions AS session
      INNER JOIN accounts AS account ON account.account_id = session.account_id
      WHERE session.token_hash = $1
      LIMIT 1
    `,
    [tokenHash],
  );
  const row = result.rows[0];
  const now = Date.now();
  if (
    !row ||
    row.revoked_at ||
    row.account_status === "deleted" ||
    row.account_status === "suspended" ||
    row.idle_expires_at.getTime() <= now ||
    row.absolute_expires_at.getTime() <= now
  ) {
    return null;
  }
  if (touch && now - row.last_seen_at.getTime() >= 5 * 60 * 1_000) {
    const nextIdle = new Date(
      Math.min(
        row.absolute_expires_at.getTime(),
        now + SESSION_IDLE_SECONDS * 1_000,
      ),
    );
    await query(
      `
        UPDATE account_sessions
        SET last_seen_at = now(), idle_expires_at = $2
        WHERE token_hash = $1
          AND revoked_at IS NULL
          AND idle_expires_at > now()
          AND absolute_expires_at > now()
      `,
      [tokenHash, nextIdle],
    );
  }
  return fromRow(row, rawToken);
}

export async function requireAccountSession(
  request: NextRequest,
): Promise<AuthenticatedAccountSession> {
  const session = await getAccountSession(request);
  if (!session) {
    throw new AccountApiError("authentication_required", 401);
  }
  return session;
}

export function csrfForAccountSession(session: AuthenticatedAccountSession): string {
  return csrfTokenForSession(session.rawToken);
}

export function setAccountSessionCookie(
  response: NextResponse,
  rawToken: string,
): void {
  response.cookies.set(ACCOUNT_SESSION_COOKIE_NAME, rawToken, accountSessionCookieOptions);
}

export function clearAccountSessionCookie(response: NextResponse): void {
  response.cookies.set(ACCOUNT_SESSION_COOKIE_NAME, "", {
    ...accountSessionCookieOptions,
    maxAge: 0,
  });
}

export async function revokeAccountSession(
  accountId: string,
  publicId: string,
): Promise<boolean> {
  return withTransaction(async (client) => {
    await client.query(
      "SELECT account_id FROM accounts WHERE account_id = $1 FOR UPDATE",
      [accountId],
    );
    const selected = await client.query<{
      token_hash: Buffer;
      legacy_session_token_hash: Buffer | null;
    }>(
      `
        SELECT token_hash, legacy_session_token_hash
        FROM account_sessions
        WHERE account_id = $1 AND public_id = $2 AND revoked_at IS NULL
        FOR UPDATE
      `,
      [accountId, publicId],
    );
    const session = selected.rows[0];
    if (!session) {
      return false;
    }
    await client.query(
      "UPDATE account_sessions SET revoked_at = now() WHERE token_hash = $1",
      [session.token_hash],
    );
    const remaining = await client.query<{ active_count: number }>(
      `
        SELECT count(*)::integer AS active_count
        FROM account_sessions
        WHERE account_id = $1
          AND token_hash <> $2
          AND revoked_at IS NULL
          AND idle_expires_at > now()
          AND absolute_expires_at > now()
      `,
      [accountId, session.token_hash],
    );
    if ((remaining.rows[0]?.active_count ?? 0) === 0) {
      await client.query(
        `
          INSERT INTO web_grant_revocations (session_token_hash, idempotency_key)
          SELECT bridge_session_token_hash, gen_random_uuid()
          FROM account_bridge_authorizations
          WHERE account_id = $1
            AND bridge_session_token_hash IS NOT NULL
          ON CONFLICT (session_token_hash) DO NOTHING
        `,
        [accountId],
      );
      await client.query(
        `
          UPDATE web_sessions AS session
          SET revoked_at = COALESCE(session.revoked_at, now())
          FROM account_bridge_authorizations AS authorization
          WHERE authorization.account_id = $1
            AND authorization.bridge_session_token_hash = session.token_hash
            AND session.session_kind = 'account_bridge'
        `,
        [accountId],
      );
      await client.query(
        `
          UPDATE account_bridge_authorizations
          SET
            state = 'revoked',
            bridge_session_token_hash = NULL,
            requested_legacy_user_key = NULL,
            lease_token = NULL,
            lease_expires_at = NULL,
            updated_at = now()
          WHERE account_id = $1
        `,
        [accountId],
      );
    }
    if (session.legacy_session_token_hash) {
      await client.query(
        `
          INSERT INTO web_grant_revocations (session_token_hash, idempotency_key)
          VALUES ($1, gen_random_uuid())
          ON CONFLICT (session_token_hash) DO NOTHING
        `,
        [session.legacy_session_token_hash],
      );
      await client.query(
        `
          UPDATE web_sessions
          SET revoked_at = now()
          WHERE token_hash = $1 AND account_id = $2 AND revoked_at IS NULL
        `,
        [session.legacy_session_token_hash, accountId],
      );
    }
    return true;
  });
}

export function isRecentlyAuthenticated(
  session: AuthenticatedAccountSession,
  now = Date.now(),
): boolean {
  return now - session.authenticatedAt.getTime() <= 10 * 60 * 1_000;
}
