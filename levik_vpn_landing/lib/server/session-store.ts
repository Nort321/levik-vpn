import "server-only";

import { randomUUID } from "node:crypto";

import type { PoolClient } from "pg";

import type {
  DeviceAuthorization,
  DeviceAuthorizationStatus,
} from "@/lib/server/bridge/auth";
import {
  decodeSecret,
  decryptString,
  encryptString,
  hmacBase64Url,
  randomToken,
  sha256,
  sha256Hex,
} from "@/lib/server/crypto";
import { query, withTransaction } from "@/lib/server/db";
import { getEnvironment } from "@/lib/server/env";

const SESSION_IDLE_SECONDS = 24 * 60 * 60;
const SESSION_ABSOLUTE_SECONDS = 30 * 24 * 60 * 60;

type LoginAttemptRow = {
  provider_mode: "legacy_bridge" | "account_local";
  bridge_poll_secret_ciphertext: string | null;
  deep_link_ciphertext: string | null;
  verification_code: string | null;
  poll_interval_seconds: number;
  expires_at: Date;
  consumed_at: Date | null;
  last_polled_at?: Date | null;
  poll_lock_until?: Date | null;
};

type SessionRow = {
  token_hash: Buffer;
  public_id: string;
  user_key: string;
  user_label: string | null;
  device_label: string;
  grant_ciphertext: string;
  grant_expires_at: Date;
  created_at: Date;
  last_seen_at: Date;
  idle_expires_at: Date;
  absolute_expires_at: Date;
  revoked_at: Date | null;
};

type LoginAttemptBase = {
  pollIntervalSeconds: number;
  expiresAt: Date;
};

export type StoredLoginAttempt = LoginAttemptBase & ({
  provider: "legacy_bridge";
  deviceCode: string;
  verificationCode: string;
  verificationUriComplete: string;
} | {
  provider: "account_local";
});

export type NewLoginAttempt = StoredLoginAttempt & {
  browserToken: string;
};

export type AuthenticatedSession = {
  publicId: string;
  rawToken: string;
  tokenHash: Buffer;
  userKey: string;
  userLabel: string;
  deviceLabel: string;
  grant: string;
  grantExpiresAt: Date;
  createdAt: Date;
  lastSeenAt: Date;
  absoluteExpiresAt: Date;
};

export type SessionListItem = {
  publicId: string;
  deviceLabel: string;
  createdAt: Date;
  lastSeenAt: Date;
  current: boolean;
};

function encryptionKey(): Buffer {
  return decodeSecret(getEnvironment().SESSION_ENCRYPTION_KEY);
}

function tokenPurpose(prefix: string, tokenHash: Buffer): string {
  return `${prefix}:v1:${tokenHash.toString("hex")}`;
}

function cleanUserLabel(label: string): string {
  const cleaned = label
    .normalize("NFC")
    .split("")
    .filter((character) => {
      const codePoint = character.codePointAt(0) ?? 0;
      return codePoint >= 32 && codePoint !== 127;
    })
    .join("")
    .trim()
    .slice(0, 160);
  return cleaned || "Пользователь Telegram";
}

function coarseDeviceLabel(userAgent: string | null): string {
  const source = (userAgent ?? "").slice(0, 512);
  const browser = /Firefox\//.test(source)
    ? "Firefox"
    : /Edg\//.test(source)
      ? "Edge"
      : /Chrome\//.test(source)
        ? "Chrome"
        : /Safari\//.test(source)
          ? "Safari"
          : "Браузер";
  const platform = /Android/.test(source)
    ? "Android"
    : /iPhone|iPad/.test(source)
      ? "iOS"
      : /Windows/.test(source)
        ? "Windows"
        : /Macintosh|Mac OS X/.test(source)
          ? "macOS"
          : /Linux/.test(source)
            ? "Linux"
            : "устройство";
  return `${browser} · ${platform}`;
}

function addressKey(address: string | null): string | null {
  if (!address) {
    return null;
  }
  return `ip_${hmacBase64Url(
    decodeSecret(getEnvironment().AUDIT_HMAC_KEY),
    `client-address:v1:${address}`,
  ).slice(0, 32)}`;
}

export async function createLoginAttempt(
  authorization: DeviceAuthorization,
): Promise<NewLoginAttempt> {
  const browserToken = randomToken();
  const tokenHash = sha256(browserToken);
  const purpose = tokenPurpose("login-attempt", tokenHash);
  const expiresAt = new Date(Date.now() + authorization.expiresIn * 1_000);

  await query(
    `
      INSERT INTO web_login_attempts (
        token_hash,
        provider_mode,
        bridge_challenge_id,
        bridge_poll_secret_ciphertext,
        deep_link_ciphertext,
        verification_code,
        poll_interval_seconds,
        expires_at
      )
      VALUES ($1, 'legacy_bridge', $2, $3, $4, $5, $6, $7)
    `,
    [
      tokenHash,
      sha256Hex(authorization.deviceCode),
      encryptString(
        authorization.deviceCode,
        encryptionKey(),
        `${purpose}:device-code`,
      ),
      encryptString(
        authorization.verificationUriComplete,
        encryptionKey(),
        `${purpose}:deep-link`,
      ),
      authorization.userCode,
      authorization.interval,
      expiresAt,
    ],
  );

  return {
    provider: "legacy_bridge",
    browserToken,
    deviceCode: authorization.deviceCode,
    verificationCode: authorization.userCode,
    verificationUriComplete: authorization.verificationUriComplete,
    pollIntervalSeconds: authorization.interval,
    expiresAt,
  };
}

export async function createLocalAccountLoginAttempt(): Promise<NewLoginAttempt> {
  const browserToken = randomToken();
  const expiresAt = new Date(Date.now() + 10 * 60 * 1_000);
  await query(
    `
      INSERT INTO web_login_attempts (
        token_hash,
        provider_mode,
        bridge_challenge_id,
        bridge_poll_secret_ciphertext,
        deep_link_ciphertext,
        verification_code,
        poll_interval_seconds,
        expires_at
      )
      VALUES ($1, 'account_local', NULL, NULL, NULL, NULL, 2, $2)
    `,
    [sha256(browserToken), expiresAt],
  );
  return {
    provider: "account_local",
    browserToken,
    pollIntervalSeconds: 2,
    expiresAt,
  };
}

function storedLoginAttempt(
  row: LoginAttemptRow,
  tokenHash: Buffer,
): StoredLoginAttempt | null {
  if (row.provider_mode === "account_local") {
    return {
      provider: "account_local",
      pollIntervalSeconds: row.poll_interval_seconds,
      expiresAt: row.expires_at,
    };
  }
  if (
    !row.bridge_poll_secret_ciphertext ||
    !row.deep_link_ciphertext ||
    !row.verification_code
  ) {
    return null;
  }
  const purpose = tokenPurpose("login-attempt", tokenHash);
  try {
    return {
      provider: "legacy_bridge",
      deviceCode: decryptString(
        row.bridge_poll_secret_ciphertext,
        encryptionKey(),
        `${purpose}:device-code`,
      ),
      verificationCode: row.verification_code,
      verificationUriComplete: decryptString(
        row.deep_link_ciphertext,
        encryptionKey(),
        `${purpose}:deep-link`,
      ),
      pollIntervalSeconds: row.poll_interval_seconds,
      expiresAt: row.expires_at,
    };
  } catch {
    return null;
  }
}

export async function getLoginAttempt(
  browserToken: string,
): Promise<StoredLoginAttempt | null> {
  if (!/^[A-Za-z0-9_-]{43}$/.test(browserToken)) {
    return null;
  }
  const tokenHash = sha256(browserToken);
  const result = await query<LoginAttemptRow>(
    `
      SELECT
        provider_mode,
        bridge_poll_secret_ciphertext,
        deep_link_ciphertext,
        verification_code,
        poll_interval_seconds,
        expires_at,
        consumed_at
      FROM web_login_attempts
      WHERE token_hash = $1
      LIMIT 1
    `,
    [tokenHash],
  );
  const row = result.rows[0];
  if (!row || row.consumed_at || row.expires_at.getTime() <= Date.now()) {
    return null;
  }

  return storedLoginAttempt(row, tokenHash);
}

export async function claimLoginAttemptForPolling(
  browserToken: string,
): Promise<StoredLoginAttempt | null> {
  if (!/^[A-Za-z0-9_-]{43}$/.test(browserToken)) {
    return null;
  }
  const tokenHash = sha256(browserToken);
  const result = await query<LoginAttemptRow>(
    `
      UPDATE web_login_attempts
      SET
        last_polled_at = now(),
        poll_lock_until = now() + interval '15 seconds'
      WHERE token_hash = $1
        AND consumed_at IS NULL
        AND expires_at > now()
        AND (
          poll_lock_until IS NULL
          OR poll_lock_until <= now()
        )
        AND (
          last_polled_at IS NULL
          OR last_polled_at <=
            now() - (poll_interval_seconds * interval '1 second')
        )
      RETURNING
        provider_mode,
        bridge_poll_secret_ciphertext,
        deep_link_ciphertext,
        verification_code,
        poll_interval_seconds,
        expires_at,
        consumed_at,
        last_polled_at,
        poll_lock_until
    `,
    [tokenHash],
  );
  const row = result.rows[0];
  if (!row) {
    return null;
  }

  const attempt = storedLoginAttempt(row, tokenHash);
  if (!attempt) {
    await releaseLoginPoll(browserToken);
    return null;
  }
  return attempt;
}

export async function releaseLoginPoll(browserToken: string): Promise<void> {
  if (!/^[A-Za-z0-9_-]{43}$/.test(browserToken)) {
    return;
  }
  await query(
    `
      UPDATE web_login_attempts
      SET poll_lock_until = NULL
      WHERE token_hash = $1
        AND consumed_at IS NULL
    `,
    [sha256(browserToken)],
  );
}

async function lockLoginAttempt(
  client: PoolClient,
  tokenHash: Buffer,
): Promise<LoginAttemptRow | null> {
  const result = await client.query<LoginAttemptRow>(
    `
      SELECT
        provider_mode,
        bridge_poll_secret_ciphertext,
        deep_link_ciphertext,
        verification_code,
        poll_interval_seconds,
        expires_at,
        consumed_at,
        last_polled_at,
        poll_lock_until
      FROM web_login_attempts
      WHERE token_hash = $1
      FOR UPDATE
    `,
    [tokenHash],
  );
  return result.rows[0] ?? null;
}

export async function createSessionFromAuthorization(
  browserToken: string,
  authorization: Extract<
    DeviceAuthorizationStatus,
    { status: "authorized" }
  >,
  requestContext: {
    userAgent: string | null;
    clientAddress: string | null;
  },
): Promise<AuthenticatedSession | null> {
  if (!/^[A-Za-z0-9_-]{43}$/.test(browserToken)) {
    return null;
  }
  const loginTokenHash = sha256(browserToken);

  return withTransaction(async (client) => {
    const attempt = await lockLoginAttempt(client, loginTokenHash);
    if (
      !attempt ||
      attempt.consumed_at ||
      attempt.expires_at.getTime() <= Date.now()
    ) {
      return null;
    }

    const now = new Date();
    const grantExpiresAt = new Date(
      now.getTime() + authorization.grantExpiresIn * 1_000,
    );
    const absoluteExpiresAt = new Date(
      Math.min(
        grantExpiresAt.getTime(),
        now.getTime() + SESSION_ABSOLUTE_SECONDS * 1_000,
      ),
    );
    const idleExpiresAt = new Date(
      Math.min(
        absoluteExpiresAt.getTime(),
        now.getTime() + SESSION_IDLE_SECONDS * 1_000,
      ),
    );
    const sessionToken = randomToken();
    const sessionTokenHash = sha256(sessionToken);
    const publicId = randomUUID();
    const deviceLabel = coarseDeviceLabel(requestContext.userAgent);
    const userLabel = cleanUserLabel(authorization.user.userLabel);

    await client.query(
      `
        INSERT INTO web_sessions (
          token_hash,
          public_id,
          user_key,
          user_label,
          device_label,
          last_ip_key,
          grant_ciphertext,
          grant_expires_at,
          created_at,
          last_seen_at,
          idle_expires_at,
          absolute_expires_at
        )
        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $9, $10, $11)
      `,
      [
        sessionTokenHash,
        publicId,
        authorization.user.userKey,
        userLabel,
        deviceLabel,
        addressKey(requestContext.clientAddress),
        encryptString(
          authorization.grant,
          encryptionKey(),
          tokenPurpose("session-grant", sessionTokenHash),
        ),
        grantExpiresAt,
        now,
        idleExpiresAt,
        absoluteExpiresAt,
      ],
    );
    await client.query(
      `
        UPDATE web_login_attempts
        SET consumed_at = $2
        WHERE token_hash = $1
      `,
      [loginTokenHash, now],
    );

    return {
      publicId,
      rawToken: sessionToken,
      tokenHash: sessionTokenHash,
      userKey: authorization.user.userKey,
      userLabel,
      deviceLabel,
      grant: authorization.grant,
      grantExpiresAt,
      createdAt: now,
      lastSeenAt: now,
      absoluteExpiresAt,
    };
  });
}

export async function getSessionByToken(
  rawToken: string,
  touch = true,
): Promise<AuthenticatedSession | null> {
  if (!/^[A-Za-z0-9_-]{43}$/.test(rawToken)) {
    return null;
  }
  const tokenHash = sha256(rawToken);
  const result = await query<SessionRow>(
    `
      SELECT
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
        revoked_at
      FROM web_sessions
      WHERE token_hash = $1
        AND session_kind = 'legacy'
      LIMIT 1
    `,
    [tokenHash],
  );
  const row = result.rows[0];
  const now = Date.now();
  if (
    !row ||
    row.revoked_at ||
    row.idle_expires_at.getTime() <= now ||
    row.absolute_expires_at.getTime() <= now ||
    row.grant_expires_at.getTime() <= now
  ) {
    return null;
  }

  let grant: string;
  try {
    grant = decryptString(
      row.grant_ciphertext,
      encryptionKey(),
      tokenPurpose("session-grant", tokenHash),
    );
  } catch {
    return null;
  }

  if (touch && now - row.last_seen_at.getTime() >= 5 * 60 * 1_000) {
    const nextIdleExpiry = new Date(
      Math.min(
        row.absolute_expires_at.getTime(),
        now + SESSION_IDLE_SECONDS * 1_000,
      ),
    );
    await query(
      `
        UPDATE web_sessions
        SET last_seen_at = now(), idle_expires_at = $2
        WHERE token_hash = $1
          AND revoked_at IS NULL
          AND idle_expires_at > now()
          AND absolute_expires_at > now()
      `,
      [tokenHash, nextIdleExpiry],
    );
  }

  return {
    publicId: row.public_id,
    rawToken,
    tokenHash,
    userKey: row.user_key,
    userLabel: row.user_label ?? "Пользователь Telegram",
    deviceLabel: row.device_label,
    grant,
    grantExpiresAt: row.grant_expires_at,
    createdAt: row.created_at,
    lastSeenAt: row.last_seen_at,
    absoluteExpiresAt: row.absolute_expires_at,
  };
}

export async function listSessions(
  session: AuthenticatedSession,
): Promise<SessionListItem[]> {
  const result = await query<
    Pick<
      SessionRow,
      "token_hash" | "public_id" | "device_label" | "created_at" | "last_seen_at"
    >
  >(
    `
      SELECT token_hash, public_id, device_label, created_at, last_seen_at
      FROM web_sessions
      WHERE user_key = $1
        AND session_kind = 'legacy'
        AND revoked_at IS NULL
        AND idle_expires_at > now()
        AND absolute_expires_at > now()
        AND grant_expires_at > now()
      ORDER BY last_seen_at DESC
      LIMIT 50
    `,
    [session.userKey],
  );

  return result.rows.map((row) => ({
    publicId: row.public_id,
    deviceLabel: row.device_label,
    createdAt: row.created_at,
    lastSeenAt: row.last_seen_at,
    current: row.token_hash.equals(session.tokenHash),
  }));
}

export async function revokeSessions(
  session: AuthenticatedSession,
  selection: { publicId?: string; others?: boolean },
): Promise<
  Array<{ tokenHash: Buffer; grant: string; idempotencyKey: string }>
> {
  return withTransaction(async (client) => {
    let predicate = "token_hash = $2";
    let parameter: Buffer | string = session.tokenHash;
    if (selection.others) {
      predicate = "token_hash <> $2";
    } else if (selection.publicId) {
      predicate = "public_id = $2";
      parameter = selection.publicId;
    }

    const result = await client.query<SessionRow>(
      `
        SELECT
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
          revoked_at
        FROM web_sessions
        WHERE user_key = $1
          AND session_kind = 'legacy'
          AND ${predicate}
          AND revoked_at IS NULL
        FOR UPDATE
      `,
      [session.userKey, parameter],
    );

    const revoked: Array<{
      tokenHash: Buffer;
      grant: string;
      idempotencyKey: string;
    }> = [];
    for (const row of result.rows) {
      let grant: string;
      try {
        grant = decryptString(
          row.grant_ciphertext,
          encryptionKey(),
          tokenPurpose("session-grant", row.token_hash),
        );
      } catch {
        continue;
      }
      const idempotencyKey = randomUUID();
      await client.query(
        `
          UPDATE web_sessions
          SET revoked_at = now()
          WHERE token_hash = $1
        `,
        [row.token_hash],
      );
      await client.query(
        `
          INSERT INTO web_grant_revocations (
            session_token_hash,
            idempotency_key
          )
          VALUES ($1, $2)
          ON CONFLICT (session_token_hash) DO NOTHING
        `,
        [row.token_hash, idempotencyKey],
      );
      revoked.push({ tokenHash: row.token_hash, grant, idempotencyKey });
    }
    return revoked;
  });
}

export async function markGrantRevoked(tokenHash: Buffer): Promise<void> {
  await query(
    `
      UPDATE web_grant_revocations
      SET completed_at = now(), last_error_code = NULL
      WHERE session_token_hash = $1
    `,
    [tokenHash],
  );
}

export async function markGrantRevocationFailed(
  tokenHash: Buffer,
  errorCode: string,
): Promise<void> {
  await query(
    `
      UPDATE web_grant_revocations
      SET
        attempts = attempts + 1,
        next_attempt_at = now() + (
          LEAST(3600, (30 * power(2, LEAST(attempts, 7)))) * interval '1 second'
        ),
        last_error_code = $2
      WHERE session_token_hash = $1
        AND completed_at IS NULL
    `,
    [tokenHash, errorCode.slice(0, 80)],
  );
}
