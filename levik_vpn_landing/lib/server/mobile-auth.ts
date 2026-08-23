import "server-only";

import type { KeyObject } from "node:crypto";

import type { AuthenticatedSession } from "@/lib/server/browser-auth";
import { sha256 } from "@/lib/server/crypto";
import { query, withTransaction } from "@/lib/server/db";
import {
  assertFreshMobileTimestamp,
  MobileApiError,
  type MobileDeviceMetadata,
} from "@/lib/server/mobile-api";
import {
  mobileRequestCanonical,
  type MobileProfileEncryptionAlgorithm,
  type MobileRequestProof,
  type MobileRequestSigningAlgorithm,
  parseMobilePublicKey,
  type ParsedMobilePublicKey,
  verifyMobileRequestSignature,
} from "@/lib/server/mobile-crypto";
import { getSessionByToken } from "@/lib/server/session-store";

type MobileBindingRow = {
  device_id: string;
  public_key_spki: Buffer;
  device_label: string;
  app_version: string;
  os_version: string;
  device_model: string;
  request_signing_algorithm: MobileRequestSigningAlgorithm;
  profile_encryption_algorithm: MobileProfileEncryptionAlgorithm;
};

export type AuthenticatedMobileRequest = {
  session: AuthenticatedSession;
  deviceId: string;
  publicKey: KeyObject;
  device: MobileDeviceMetadata;
  requestSigningAlgorithm: MobileRequestSigningAlgorithm;
  profileEncryptionAlgorithm: MobileProfileEncryptionAlgorithm;
};

function storedBinding(row: MobileBindingRow): {
  deviceId: string;
  publicKey: KeyObject;
  device: MobileDeviceMetadata;
  requestSigningAlgorithm: MobileRequestSigningAlgorithm;
  profileEncryptionAlgorithm: MobileProfileEncryptionAlgorithm;
} {
  let parsed: ParsedMobilePublicKey;
  try {
    parsed = parseMobilePublicKey(row.public_key_spki.toString("base64url"));
  } catch {
    throw new MobileApiError("invalid_device_binding", 401);
  }
  if (parsed.deviceId !== row.device_id) {
    throw new MobileApiError("invalid_device_binding", 401);
  }
  return {
    deviceId: row.device_id,
    publicKey: parsed.key,
    device: {
      label: row.device_label,
      appVersion: row.app_version,
      osVersion: row.os_version,
      model: row.device_model,
    },
    requestSigningAlgorithm: row.request_signing_algorithm,
    profileEncryptionAlgorithm: row.profile_encryption_algorithm,
  };
}

async function consumeRegistrationNonce(
  deviceId: string,
  nonce: string,
): Promise<void> {
  const inserted = await withTransaction(async (client) => {
    await client.query(
      "DELETE FROM mobile_registration_nonces WHERE expires_at <= now()",
    );
    return client.query(
      `
        INSERT INTO mobile_registration_nonces (
          device_id,
          nonce_hash,
          expires_at
        )
        VALUES ($1, $2, now() + interval '5 minutes')
        ON CONFLICT (device_id, nonce_hash) DO NOTHING
        RETURNING device_id
      `,
      [deviceId, sha256(nonce)],
    );
  });
  if (inserted.rowCount !== 1) {
    throw new MobileApiError("replayed_request", 409);
  }
}

async function consumeLoginNonce(
  loginTokenHash: Buffer,
  nonce: string,
): Promise<void> {
  const inserted = await withTransaction(async (client) => {
    await client.query(
      `
        DELETE FROM mobile_login_nonces
        WHERE login_token_hash = $1
          AND expires_at <= now()
      `,
      [loginTokenHash],
    );
    return client.query(
      `
        INSERT INTO mobile_login_nonces (
          login_token_hash,
          nonce_hash,
          expires_at
        )
        VALUES ($1, $2, now() + interval '5 minutes')
        ON CONFLICT (login_token_hash, nonce_hash) DO NOTHING
        RETURNING login_token_hash
      `,
      [loginTokenHash, sha256(nonce)],
    );
  });
  if (inserted.rowCount !== 1) {
    throw new MobileApiError("replayed_request", 409);
  }
}

async function consumeSessionNonce(
  sessionTokenHash: Buffer,
  nonce: string,
): Promise<void> {
  const inserted = await withTransaction(async (client) => {
    await client.query(
      `
        DELETE FROM mobile_session_nonces
        WHERE session_token_hash = $1
          AND expires_at <= now()
      `,
      [sessionTokenHash],
    );
    return client.query(
      `
        INSERT INTO mobile_session_nonces (
          session_token_hash,
          nonce_hash,
          expires_at
        )
        VALUES ($1, $2, now() + interval '5 minutes')
        ON CONFLICT (session_token_hash, nonce_hash) DO NOTHING
        RETURNING session_token_hash
      `,
      [sessionTokenHash, sha256(nonce)],
    );
  });
  if (inserted.rowCount !== 1) {
    throw new MobileApiError("replayed_request", 409);
  }
}

function assertValidSignature(
  key: KeyObject,
  proof: MobileRequestProof,
  method: string,
  path: string,
  accessToken: string,
  body: Buffer,
  algorithm: MobileRequestSigningAlgorithm,
): void {
  assertFreshMobileTimestamp(proof.timestamp);
  const canonical = mobileRequestCanonical(
    method,
    path,
    proof,
    accessToken,
    body,
  );
  if (
    !verifyMobileRequestSignature(
      key,
      canonical,
      proof.signature,
      algorithm,
    )
  ) {
    throw new MobileApiError("invalid_request_signature", 401);
  }
}

export async function authenticateMobileRegistration(
  publicKeySpki: string,
  proof: MobileRequestProof,
  method: string,
  path: string,
  body: Buffer,
  requestSigningAlgorithm: MobileRequestSigningAlgorithm,
): Promise<ParsedMobilePublicKey> {
  let publicKey: ParsedMobilePublicKey;
  try {
    publicKey = parseMobilePublicKey(publicKeySpki);
  } catch {
    throw new MobileApiError("invalid_public_key", 400);
  }
  if (publicKey.deviceId !== proof.deviceId) {
    throw new MobileApiError("invalid_device_id", 400);
  }
  assertValidSignature(
    publicKey.key,
    proof,
    method,
    path,
    "",
    body,
    requestSigningAlgorithm,
  );
  await consumeRegistrationNonce(publicKey.deviceId, proof.nonce);
  return publicKey;
}

export async function bindMobileLogin(
  loginToken: string,
  publicKey: ParsedMobilePublicKey,
  device: MobileDeviceMetadata,
  algorithms: {
    requestSigning: MobileRequestSigningAlgorithm;
    profileEncryption: MobileProfileEncryptionAlgorithm;
  },
): Promise<void> {
  const result = await query(
    `
      INSERT INTO mobile_login_bindings (
        login_token_hash,
        device_id,
        public_key_spki,
        device_label,
        app_version,
        os_version,
        device_model,
        request_signing_algorithm,
        profile_encryption_algorithm
      )
      SELECT
        token_hash,
        $2,
        $3,
        $4,
        $5,
        $6,
        $7,
        $8,
        $9
      FROM web_login_attempts
      WHERE token_hash = $1
        AND consumed_at IS NULL
        AND expires_at > now()
      RETURNING login_token_hash
    `,
    [
      sha256(loginToken),
      publicKey.deviceId,
      publicKey.der,
      device.label,
      device.appVersion,
      device.osVersion,
      device.model,
      algorithms.requestSigning,
      algorithms.profileEncryption,
    ],
  );
  if (result.rowCount !== 1) {
    throw new MobileApiError("login_expired", 410);
  }
}

export async function authenticateMobileLoginRequest(
  loginToken: string,
  proof: MobileRequestProof,
  method: string,
  path: string,
  body: Buffer,
): Promise<{
  loginTokenHash: Buffer;
  deviceId: string;
  publicKey: KeyObject;
  device: MobileDeviceMetadata;
  requestSigningAlgorithm: MobileRequestSigningAlgorithm;
  profileEncryptionAlgorithm: MobileProfileEncryptionAlgorithm;
}> {
  const loginTokenHash = sha256(loginToken);
  const result = await query<MobileBindingRow>(
    `
      SELECT
        binding.device_id,
        binding.public_key_spki,
        binding.device_label,
        binding.app_version,
        binding.os_version,
        binding.device_model,
        binding.request_signing_algorithm,
        binding.profile_encryption_algorithm
      FROM mobile_login_bindings AS binding
      INNER JOIN web_login_attempts AS attempt
        ON attempt.token_hash = binding.login_token_hash
      WHERE binding.login_token_hash = $1
        AND binding.device_id = $2
        AND attempt.consumed_at IS NULL
        AND attempt.expires_at > now()
      LIMIT 1
    `,
    [loginTokenHash, proof.deviceId],
  );
  const row = result.rows[0];
  if (!row) {
    throw new MobileApiError("login_expired", 410);
  }
  const binding = storedBinding(row);
  assertValidSignature(
    binding.publicKey,
    proof,
    method,
    path,
    "",
    body,
    binding.requestSigningAlgorithm,
  );
  await consumeLoginNonce(loginTokenHash, proof.nonce);
  return { loginTokenHash, ...binding };
}

export async function promoteMobileLogin(
  loginToken: string,
  session: AuthenticatedSession,
): Promise<void> {
  const loginTokenHash = sha256(loginToken);
  await withTransaction(async (client) => {
    const binding = await client.query<MobileBindingRow>(
      `
        SELECT
          device_id,
          public_key_spki,
          device_label,
          app_version,
          os_version,
          device_model,
          request_signing_algorithm,
          profile_encryption_algorithm
        FROM mobile_login_bindings
        WHERE login_token_hash = $1
        FOR UPDATE
      `,
      [loginTokenHash],
    );
    const row = binding.rows[0];
    if (!row) {
      throw new MobileApiError("login_expired", 410);
    }

    const inserted = await client.query(
      `
        INSERT INTO mobile_session_bindings (
          session_token_hash,
          device_id,
          public_key_spki,
          device_label,
          app_version,
          os_version,
          device_model,
          request_signing_algorithm,
          profile_encryption_algorithm
        )
        VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
        RETURNING session_token_hash
      `,
      [
        session.tokenHash,
        row.device_id,
        row.public_key_spki,
        row.device_label,
        row.app_version,
        row.os_version,
        row.device_model,
        row.request_signing_algorithm,
        row.profile_encryption_algorithm,
      ],
    );
    const updated = await client.query(
      `
        UPDATE web_sessions
        SET
          device_label = $2,
          last_seen_at = now(),
          idle_expires_at = absolute_expires_at
        WHERE token_hash = $1
          AND revoked_at IS NULL
          AND absolute_expires_at > now()
          AND grant_expires_at > now()
      `,
      [session.tokenHash, row.device_label],
    );
    if (inserted.rowCount !== 1 || updated.rowCount !== 1) {
      throw new MobileApiError("login_expired", 410);
    }
  });
}

export async function authenticateMobileSessionRequest(
  accessToken: string,
  proof: MobileRequestProof,
  method: string,
  path: string,
  body: Buffer,
): Promise<AuthenticatedMobileRequest> {
  const sessionTokenHash = sha256(accessToken);
  const result = await query<MobileBindingRow>(
    `
      SELECT
        device_id,
        public_key_spki,
        device_label,
        app_version,
        os_version,
        device_model,
        request_signing_algorithm,
        profile_encryption_algorithm
      FROM mobile_session_bindings
      WHERE session_token_hash = $1
        AND device_id = $2
      LIMIT 1
    `,
    [sessionTokenHash, proof.deviceId],
  );
  const row = result.rows[0];
  if (!row) {
    throw new MobileApiError("authentication_required", 401);
  }
  const binding = storedBinding(row);
  assertValidSignature(
    binding.publicKey,
    proof,
    method,
    path,
    accessToken,
    body,
    binding.requestSigningAlgorithm,
  );
  await consumeSessionNonce(sessionTokenHash, proof.nonce);

  const session = await getSessionByToken(accessToken, false);
  if (!session) {
    throw new MobileApiError("session_expired", 401);
  }
  const touched = await query(
    `
      UPDATE web_sessions
      SET
        last_seen_at = now(),
        idle_expires_at = absolute_expires_at
      WHERE token_hash = $1
        AND revoked_at IS NULL
        AND idle_expires_at > now()
        AND absolute_expires_at > now()
        AND grant_expires_at > now()
    `,
    [sessionTokenHash],
  );
  if (touched.rowCount !== 1) {
    throw new MobileApiError("session_expired", 401);
  }
  return { session, ...binding };
}
