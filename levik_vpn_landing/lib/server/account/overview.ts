import "server-only";

import { AccountApiError } from "@/lib/server/account/errors";
import { publicAccount, getAccountById } from "@/lib/server/account/model";
import {
  csrfForAccountSession,
  type AuthenticatedAccountSession,
} from "@/lib/server/account/session";
import { query } from "@/lib/server/db";

type IdentityRow = {
  identity_id: string;
  provider: string;
  label: string;
  verified_at: Date;
  last_used_at: Date | null;
};

type PasskeyRow = {
  credential_id: string;
  name: string;
  created_at: Date;
  last_used_at: Date | null;
};

type SessionRow = {
  token_hash: Buffer;
  public_id: string;
  device_name: string;
  created_at: Date;
  last_seen_at: Date;
};

type DeviceRow = {
  device_id: string;
  name: string;
  platform: string;
  created_at: Date;
  last_seen_at: Date;
  current: boolean;
};

type EntitlementRow = {
  entitlement_id: string;
  source: string;
  status: string;
  expires_at: Date | null;
};

export async function getAccountOverview(session: AuthenticatedAccountSession) {
  const account = await getAccountById(session.accountId);
  if (!account || account.status === "deleted" || account.status === "suspended") {
    throw new AccountApiError("account_not_found", 404);
  }

  const [identities, passkeys, sessions, devices, recovery, entitlements] =
    await Promise.all([
      query<IdentityRow>(
        `
          SELECT identity_id, provider, label, verified_at, last_used_at
          FROM account_identities
          WHERE account_id = $1 AND revoked_at IS NULL
          ORDER BY created_at
        `,
        [session.accountId],
      ),
      query<PasskeyRow>(
        `
          SELECT credential_id, name, created_at, last_used_at
          FROM passkey_credentials
          WHERE account_id = $1 AND revoked_at IS NULL
          ORDER BY created_at DESC
        `,
        [session.accountId],
      ),
      query<SessionRow>(
        `
          SELECT token_hash, public_id, device_name, created_at, last_seen_at
          FROM account_sessions
          WHERE account_id = $1
            AND revoked_at IS NULL
            AND idle_expires_at > now()
            AND absolute_expires_at > now()
          ORDER BY last_seen_at DESC
          LIMIT 50
        `,
        [session.accountId],
      ),
      query<DeviceRow>(
        `
          SELECT
            device.device_id,
            device.external_device_id,
            device.name,
            device.platform,
            device.created_at,
            device.last_seen_at,
            EXISTS (
              SELECT 1
              FROM mobile_session_bindings AS binding
              WHERE binding.device_id = device.external_device_id
                AND binding.session_token_hash = $2
            ) AS current
          FROM account_devices AS device
          WHERE device.account_id = $1 AND device.revoked_at IS NULL
          ORDER BY device.last_seen_at DESC
          LIMIT 100
        `,
        [session.accountId, session.tokenHash],
      ),
      query<{ remaining: number }>(
        `
          SELECT count(*)::integer AS remaining
          FROM recovery_codes
          WHERE account_id = $1 AND used_at IS NULL AND revoked_at IS NULL
        `,
        [session.accountId],
      ),
      query<EntitlementRow>(
        `
          SELECT entitlement_id, source, status, expires_at
          FROM account_entitlements
          WHERE account_id = $1
          ORDER BY created_at DESC
        `,
        [session.accountId],
      ),
    ]);

  return {
    account: publicAccount(account),
    identities: identities.rows.map((identity) => ({
      id: identity.identity_id,
      provider: identity.provider,
      label: identity.label,
      verifiedAt: identity.verified_at.toISOString(),
      lastUsedAt: identity.last_used_at?.toISOString() ?? null,
    })),
    passkeys: passkeys.rows.map((passkey) => ({
      credentialId: passkey.credential_id,
      name: passkey.name,
      createdAt: passkey.created_at.toISOString(),
      lastUsedAt: passkey.last_used_at?.toISOString() ?? null,
    })),
    sessions: sessions.rows.map((item) => ({
      id: item.public_id,
      deviceName: item.device_name,
      createdAt: item.created_at.toISOString(),
      lastSeenAt: item.last_seen_at.toISOString(),
      current: item.token_hash.equals(session.tokenHash),
    })),
    devices: devices.rows.map((device) => ({
      id: device.device_id,
      name: device.name,
      platform: device.platform,
      createdAt: device.created_at.toISOString(),
      lastSeenAt: device.last_seen_at.toISOString(),
      current: device.current,
    })),
    recoveryCodesRemaining: recovery.rows[0]?.remaining ?? 0,
    entitlements: entitlements.rows.map((entitlement) => ({
      id: entitlement.entitlement_id,
      source: entitlement.source,
      status: entitlement.status,
      expiresAt: entitlement.expires_at?.toISOString() ?? null,
    })),
    csrfToken: csrfForAccountSession(session),
  };
}
