import "server-only";

import {
  generateAuthenticationOptions,
  generateRegistrationOptions,
  verifyAuthenticationResponse,
  verifyRegistrationResponse,
  type AuthenticationResponseJSON,
  type AuthenticatorTransportFuture,
  type RegistrationResponseJSON,
} from "@simplewebauthn/server";
import { z } from "zod";

import { AccountApiError, isPostgresError } from "@/lib/server/account/errors";
import { cleanDisplayText } from "@/lib/server/account/identifiers";
import {
  getAccountById,
  type AccountRecord,
} from "@/lib/server/account/model";
import { sha256 } from "@/lib/server/crypto";
import { query, withTransaction } from "@/lib/server/db";
import { getEnvironment } from "@/lib/server/env";

export const WEBAUTHN_RP_ID = "leviknet.com";
const WEBAUTHN_RP_NAME = "Levik VPN";
const CHALLENGE_LIFETIME_SECONDS = 5 * 60;

const base64UrlSchema = z.string().regex(/^[A-Za-z0-9_-]{1,8192}$/);
const transportsSchema = z.array(
  z.enum(["ble", "cable", "hybrid", "internal", "nfc", "smart-card", "usb"]),
).max(7);
const clientExtensionsSchema = z.object({}).passthrough();

const registrationResponseSchema = z
  .object({
    id: base64UrlSchema.max(1_024),
    rawId: base64UrlSchema.max(1_024),
    response: z
      .object({
        clientDataJSON: base64UrlSchema,
        attestationObject: base64UrlSchema,
        authenticatorData: base64UrlSchema.optional(),
        transports: transportsSchema.optional(),
        publicKeyAlgorithm: z.number().int().optional(),
        publicKey: base64UrlSchema.optional(),
      })
      .strict(),
    authenticatorAttachment: z.enum(["cross-platform", "platform"]).optional(),
    clientExtensionResults: clientExtensionsSchema,
    type: z.literal("public-key"),
  })
  .strict();

const authenticationResponseSchema = z
  .object({
    id: base64UrlSchema.max(1_024),
    rawId: base64UrlSchema.max(1_024),
    response: z
      .object({
        clientDataJSON: base64UrlSchema,
        authenticatorData: base64UrlSchema,
        signature: base64UrlSchema,
        userHandle: base64UrlSchema.optional(),
      })
      .strict(),
    authenticatorAttachment: z.enum(["cross-platform", "platform"]).optional(),
    clientExtensionResults: clientExtensionsSchema,
    type: z.literal("public-key"),
  })
  .strict();

export function parseRegistrationResponse(value: unknown): RegistrationResponseJSON {
  const parsed = registrationResponseSchema.safeParse(value);
  if (!parsed.success) {
    throw new AccountApiError("invalid_passkey_response", 400);
  }
  return parsed.data;
}

export function parseAuthenticationResponse(value: unknown): AuthenticationResponseJSON {
  const parsed = authenticationResponseSchema.safeParse(value);
  if (!parsed.success) {
    throw new AccountApiError("invalid_passkey_response", 400);
  }
  return parsed.data;
}

type PasskeyRow = {
  credential_id: string;
  account_id: string;
  public_key: Buffer;
  signature_counter: string | number;
  transports: AuthenticatorTransportFuture[];
  name: string;
  created_at: Date;
  last_used_at: Date | null;
};

type ChallengeRow = {
  account_id: string | null;
  expires_at: Date;
  consumed_at: Date | null;
};

function uuidBytes(uuid: string): Uint8Array<ArrayBuffer> {
  const bytes = new Uint8Array(16);
  bytes.set(Buffer.from(uuid.replaceAll("-", ""), "hex"));
  return bytes;
}

function expectedOrigin(): string {
  const environment = getEnvironment();
  if (environment.NODE_ENV === "production") {
    if (environment.APP_ORIGIN !== `https://${WEBAUTHN_RP_ID}`) {
      throw new Error("Production APP_ORIGIN must match the WebAuthn RP origin");
    }
    return environment.APP_ORIGIN;
  }
  return environment.APP_ORIGIN;
}

async function storeChallenge(
  challenge: string,
  flow: "authentication" | "registration",
  accountId: string | null,
): Promise<string> {
  const result = await query<{ ceremony_id: string }>(
    `
      INSERT INTO webauthn_challenges (
        challenge_hash,
        account_id,
        flow,
        expires_at
      )
      VALUES ($1, $2, $3, now() + ($4 * interval '1 second'))
      RETURNING ceremony_id
    `,
    [sha256(challenge), accountId, flow, CHALLENGE_LIFETIME_SECONDS],
  );
  const ceremonyId = result.rows[0]?.ceremony_id;
  if (!ceremonyId) {
    throw new Error("WebAuthn challenge insert did not return a ceremony id");
  }
  return ceremonyId;
}

async function challengeRow(
  ceremonyId: string,
  flow: "authentication" | "registration",
): Promise<ChallengeRow> {
  const result = await query<ChallengeRow>(
    `
      SELECT account_id, expires_at, consumed_at
      FROM webauthn_challenges
      WHERE ceremony_id = $1 AND flow = $2
      LIMIT 1
    `,
    [ceremonyId, flow],
  );
  const row = result.rows[0];
  if (!row || row.consumed_at || row.expires_at.getTime() <= Date.now()) {
    throw new AccountApiError("auth_challenge_expired", 409);
  }
  return row;
}

function challengeConsumer(
  ceremonyId: string,
  flow: "authentication" | "registration",
  accountId: string | null,
): (challenge: string) => Promise<boolean> {
  return async (challenge) => {
    const result = await query(
      `
        UPDATE webauthn_challenges
        SET consumed_at = now()
        WHERE ceremony_id = $1
          AND challenge_hash = $2
          AND flow = $3
          AND account_id IS NOT DISTINCT FROM $4::uuid
          AND consumed_at IS NULL
          AND expires_at > now()
      `,
      [ceremonyId, sha256(challenge), flow, accountId],
    );
    return result.rowCount === 1;
  };
}

async function activePasskeys(accountId: string): Promise<PasskeyRow[]> {
  const result = await query<PasskeyRow>(
    `
      SELECT
        credential_id,
        account_id,
        public_key,
        signature_counter,
        transports,
        name,
        created_at,
        last_used_at
      FROM passkey_credentials
      WHERE account_id = $1 AND revoked_at IS NULL
      ORDER BY created_at DESC
    `,
    [accountId],
  );
  return result.rows;
}

export async function createPasskeyRegistrationOptions(
  accountId: string,
): Promise<{ ceremonyId: string; options: Awaited<ReturnType<typeof generateRegistrationOptions>> }> {
  const account = await getAccountById(accountId);
  if (!account || !["active", "deletion_pending"].includes(account.status)) {
    throw new AccountApiError("account_not_found", 404);
  }
  const existing = await activePasskeys(accountId);
  const options = await generateRegistrationOptions({
    rpName: WEBAUTHN_RP_NAME,
    rpID: WEBAUTHN_RP_ID,
    userID: uuidBytes(account.accountId),
    userName: account.levikId,
    userDisplayName: account.displayName,
    timeout: CHALLENGE_LIFETIME_SECONDS * 1_000,
    attestationType: "none",
    excludeCredentials: existing.map((credential) => ({
      id: credential.credential_id,
      transports: credential.transports,
    })),
    authenticatorSelection: {
      residentKey: "required",
      userVerification: "required",
    },
  });
  return {
    ceremonyId: await storeChallenge(options.challenge, "registration", accountId),
    options,
  };
}

export async function verifyPasskeyRegistration(input: {
  accountId: string;
  ceremonyId: string;
  response: RegistrationResponseJSON;
  name: string;
}) {
  const challenge = await challengeRow(input.ceremonyId, "registration");
  if (challenge.account_id !== input.accountId) {
    throw new AccountApiError("auth_challenge_expired", 409);
  }
  let verification;
  try {
    verification = await verifyRegistrationResponse({
      response: input.response,
      expectedChallenge: challengeConsumer(
        input.ceremonyId,
        "registration",
        input.accountId,
      ),
      expectedOrigin: expectedOrigin(),
      expectedRPID: WEBAUTHN_RP_ID,
      requireUserVerification: true,
    });
  } catch {
    throw new AccountApiError("invalid_passkey_response", 400);
  }
  if (!verification.verified) {
    throw new AccountApiError("invalid_passkey_response", 400);
  }
  const { credential, credentialBackedUp, credentialDeviceType } =
    verification.registrationInfo;
  const now = new Date();
  try {
    await query(
      `
        INSERT INTO passkey_credentials (
          credential_id,
          account_id,
          public_key,
          signature_counter,
          transports,
          device_type,
          backed_up,
          name,
          created_at
        )
        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
      `,
      [
        credential.id,
        input.accountId,
        Buffer.from(credential.publicKey),
        credential.counter,
        credential.transports ?? [],
        credentialDeviceType,
        credentialBackedUp,
        cleanDisplayText(input.name, 120, "Passkey"),
        now,
      ],
    );
  } catch (error) {
    if (isPostgresError(error, "23505")) {
      throw new AccountApiError("credential_conflict", 409);
    }
    throw error;
  }
  return {
    credentialId: credential.id,
    name: cleanDisplayText(input.name, 120, "Passkey"),
    createdAt: now.toISOString(),
    lastUsedAt: null,
  };
}

export async function createPasskeyAuthenticationOptions(
  _levikId?: string,
): Promise<{ ceremonyId: string; options: Awaited<ReturnType<typeof generateAuthenticationOptions>> }> {
  void _levikId;
  const options = await generateAuthenticationOptions({
    rpID: WEBAUTHN_RP_ID,
    timeout: CHALLENGE_LIFETIME_SECONDS * 1_000,
    userVerification: "required",
  });
  return {
    ceremonyId: await storeChallenge(options.challenge, "authentication", null),
    options,
  };
}

export async function verifyPasskeyAuthentication(input: {
  ceremonyId: string;
  response: AuthenticationResponseJSON;
}): Promise<AccountRecord> {
  const challenge = await challengeRow(input.ceremonyId, "authentication");
  const credentialResult = await query<PasskeyRow>(
    `
      SELECT
        credential_id,
        account_id,
        public_key,
        signature_counter,
        transports,
        name,
        created_at,
        last_used_at
      FROM passkey_credentials
      WHERE credential_id = $1 AND revoked_at IS NULL
      LIMIT 1
    `,
    [input.response.id],
  );
  const credential = credentialResult.rows[0];
  if (!credential || (challenge.account_id && challenge.account_id !== credential.account_id)) {
    throw new AccountApiError("invalid_credentials", 401);
  }
  const oldCounter = Number(credential.signature_counter);
  if (!Number.isSafeInteger(oldCounter)) {
    throw new AccountApiError("invalid_credentials", 401);
  }
  let verification;
  try {
    verification = await verifyAuthenticationResponse({
      response: input.response,
      expectedChallenge: challengeConsumer(
        input.ceremonyId,
        "authentication",
        challenge.account_id,
      ),
      expectedOrigin: expectedOrigin(),
      expectedRPID: WEBAUTHN_RP_ID,
      requireUserVerification: true,
      credential: {
        id: credential.credential_id,
        publicKey: Uint8Array.from(credential.public_key),
        counter: oldCounter,
        transports: credential.transports,
      },
    });
  } catch {
    throw new AccountApiError("invalid_credentials", 401);
  }
  if (!verification.verified || !verification.authenticationInfo.userVerified) {
    throw new AccountApiError("invalid_credentials", 401);
  }
  const updated = await query(
    `
      UPDATE passkey_credentials
      SET
        signature_counter = $2,
        backed_up = $3,
        device_type = $4,
        last_used_at = now()
      WHERE credential_id = $1
        AND account_id = $5
        AND signature_counter = $6
        AND revoked_at IS NULL
    `,
    [
      credential.credential_id,
      verification.authenticationInfo.newCounter,
      verification.authenticationInfo.credentialBackedUp,
      verification.authenticationInfo.credentialDeviceType,
      credential.account_id,
      oldCounter,
    ],
  );
  if (updated.rowCount !== 1) {
    throw new AccountApiError("replayed_credential", 409);
  }
  const account = await getAccountById(credential.account_id);
  if (!account || !["active", "deletion_pending"].includes(account.status)) {
    throw new AccountApiError("account_not_found", 404);
  }
  return account;
}

export async function renamePasskey(
  accountId: string,
  credentialId: string,
  name: string,
): Promise<void> {
  const result = await query(
    `
      UPDATE passkey_credentials
      SET name = $3
      WHERE account_id = $1 AND credential_id = $2 AND revoked_at IS NULL
    `,
    [accountId, credentialId, cleanDisplayText(name, 120, "Passkey")],
  );
  if (result.rowCount !== 1) {
    throw new AccountApiError("passkey_not_found", 404);
  }
}

export async function revokePasskey(
  accountId: string,
  credentialId: string,
): Promise<void> {
  await withTransaction(async (client) => {
    const selected = await client.query(
      `
        SELECT 1 FROM passkey_credentials
        WHERE account_id = $1 AND credential_id = $2 AND revoked_at IS NULL
        FOR UPDATE
      `,
      [accountId, credentialId],
    );
    if (selected.rowCount !== 1) {
      throw new AccountApiError("passkey_not_found", 404);
    }
    const methods = await client.query<{ method_count: number }>(
      `
        SELECT (
          SELECT count(*)::integer FROM account_identities
          WHERE account_id = $1 AND revoked_at IS NULL
        ) + (
          SELECT count(*)::integer FROM passkey_credentials
          WHERE account_id = $1 AND revoked_at IS NULL AND credential_id <> $2
        ) + (
          SELECT count(*)::integer FROM recovery_codes
          WHERE account_id = $1 AND used_at IS NULL AND revoked_at IS NULL
        ) AS method_count
      `,
      [accountId, credentialId],
    );
    if ((methods.rows[0]?.method_count ?? 0) < 1) {
      throw new AccountApiError("last_authentication_method", 409);
    }
    await client.query(
      `
        UPDATE passkey_credentials SET revoked_at = now()
        WHERE account_id = $1 AND credential_id = $2
      `,
      [accountId, credentialId],
    );
  });
}
