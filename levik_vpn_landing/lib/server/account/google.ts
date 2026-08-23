import "server-only";

import { OAuth2Client } from "google-auth-library";

import { AccountApiError, isPostgresError } from "@/lib/server/account/errors";
import { cleanDisplayText } from "@/lib/server/account/identifiers";
import { insertIdentity } from "@/lib/server/account/identity";
import {
  createAccount,
  getAccountById,
  type AccountRecord,
} from "@/lib/server/account/model";
import { constantTimeEqual, randomToken, sha256 } from "@/lib/server/crypto";
import { query, withTransaction } from "@/lib/server/db";
import { getEnvironment } from "@/lib/server/env";

const GOOGLE_ISSUERS = new Set([
  "accounts.google.com",
  "https://accounts.google.com",
]);

const googleClient = new OAuth2Client();

export async function createGoogleChallenge(): Promise<string | null> {
  if (!getEnvironment().GOOGLE_WEB_CLIENT_ID) {
    return null;
  }
  const nonce = randomToken();
  await query(
    `
      INSERT INTO account_auth_challenges (
        challenge_hash,
        purpose,
        expires_at
      )
      VALUES ($1, 'google', now() + interval '5 minutes')
    `,
    [sha256(nonce)],
  );
  return nonce;
}

async function challengeExists(nonce: string): Promise<boolean> {
  if (!/^[A-Za-z0-9_-]{43}$/.test(nonce)) {
    return false;
  }
  const result = await query(
    `
      SELECT 1
      FROM account_auth_challenges
      WHERE challenge_hash = $1
        AND purpose = 'google'
        AND consumed_at IS NULL
        AND expires_at > now()
    `,
    [sha256(nonce)],
  );
  return result.rowCount === 1;
}

async function consumeChallenge(nonce: string): Promise<void> {
  const result = await query(
    `
      UPDATE account_auth_challenges
      SET consumed_at = now()
      WHERE challenge_hash = $1
        AND purpose = 'google'
        AND consumed_at IS NULL
        AND expires_at > now()
    `,
    [sha256(nonce)],
  );
  if (result.rowCount !== 1) {
    throw new AccountApiError("auth_challenge_expired", 409);
  }
}

async function verifyGoogleIdToken(
  idToken: string,
  nonce: string,
): Promise<{ subject: string; displayName: string }> {
  const environment = getEnvironment();
  if (
    environment.googleOAuthClientIds.size === 0 ||
    idToken.length < 100 ||
    idToken.length > 16_384 ||
    !(await challengeExists(nonce))
  ) {
    throw new AccountApiError("invalid_credentials", 401);
  }

  let payload;
  try {
    const ticket = await googleClient.verifyIdToken({
      idToken,
      audience: [...environment.googleOAuthClientIds],
      maxExpiry: 24 * 60 * 60,
    });
    payload = ticket.getPayload();
  } catch {
    throw new AccountApiError("invalid_credentials", 401);
  }
  if (
    !payload ||
    !GOOGLE_ISSUERS.has(payload.iss) ||
    !/^[0-9]{6,255}$/.test(payload.sub) ||
    typeof payload.nonce !== "string" ||
    !constantTimeEqual(nonce, payload.nonce)
  ) {
    throw new AccountApiError("invalid_credentials", 401);
  }
  await consumeChallenge(nonce);
  return {
    subject: payload.sub,
    displayName: cleanDisplayText(payload.name ?? "Google user", 120, "Google user"),
  };
}

async function accountForGoogleSubject(subject: string): Promise<AccountRecord | null> {
  const result = await query<{ account_id: string }>(
    `
      SELECT account_id
      FROM account_identities
      WHERE provider = 'google'
        AND provider_subject = $1
        AND revoked_at IS NULL
      LIMIT 1
    `,
    [subject],
  );
  return result.rows[0] ? getAccountById(result.rows[0].account_id) : null;
}

export async function authenticateGoogle(
  idToken: string,
  nonce: string,
): Promise<AccountRecord> {
  const verified = await verifyGoogleIdToken(idToken, nonce);
  const existing = await accountForGoogleSubject(verified.subject);
  if (existing) {
    if (!["active", "deletion_pending"].includes(existing.status)) {
      throw new AccountApiError("account_not_found", 404);
    }
    await query(
      `
        UPDATE account_identities
        SET last_used_at = now()
        WHERE account_id = $1
          AND provider = 'google'
          AND provider_subject = $2
          AND revoked_at IS NULL
      `,
      [existing.accountId, verified.subject],
    );
    return existing;
  }

  try {
    return await withTransaction(async (client) => {
      const account = await createAccount(client, verified.displayName);
      await insertIdentity(client, {
        accountId: account.accountId,
        provider: "google",
        subject: verified.subject,
        label: "Google",
      });
      return account;
    });
  } catch (error) {
    if (!isPostgresError(error, "23505") && !(error instanceof AccountApiError)) {
      throw error;
    }
    const concurrent = await accountForGoogleSubject(verified.subject);
    if (!concurrent || !["active", "deletion_pending"].includes(concurrent.status)) {
      throw error;
    }
    return concurrent;
  }
}

export async function linkGoogleIdentity(
  accountId: string,
  idToken: string,
  nonce: string,
  label = "Google",
): Promise<void> {
  const verified = await verifyGoogleIdToken(idToken, nonce);
  const existing = await accountForGoogleSubject(verified.subject);
  if (existing && existing.accountId !== accountId) {
    throw new AccountApiError("credential_conflict", 409);
  }
  if (existing) {
    return;
  }
  await withTransaction(async (client) => {
    await insertIdentity(client, {
      accountId,
      provider: "google",
      subject: verified.subject,
      label,
    });
  });
}
