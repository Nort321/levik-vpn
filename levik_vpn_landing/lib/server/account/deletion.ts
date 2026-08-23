import "server-only";

import { randomToken, sha256 } from "@/lib/server/crypto";
import { AccountApiError } from "@/lib/server/account/errors";
import { withTransaction } from "@/lib/server/db";

const CONFIRMATION_LIFETIME_MINUTES = 10;

export async function requestAccountDeletion(accountId: string): Promise<{
  confirmationToken: string;
  expiresAt: Date;
}> {
  const confirmationToken = randomToken();
  const expiresAt = new Date(
    Date.now() + CONFIRMATION_LIFETIME_MINUTES * 60 * 1_000,
  );
  await withTransaction(async (client) => {
    const updated = await client.query(
      `
        UPDATE accounts
        SET
          status = 'deletion_pending',
          deletion_requested_at = now(),
          updated_at = now()
        WHERE account_id = $1 AND status IN ('active', 'deletion_pending')
      `,
      [accountId],
    );
    if (updated.rowCount !== 1) {
      throw new AccountApiError("account_not_found", 404);
    }
    await client.query(
      `
        UPDATE account_deletion_requests
        SET consumed_at = now()
        WHERE account_id = $1 AND consumed_at IS NULL
      `,
      [accountId],
    );
    await client.query(
      `
        INSERT INTO account_deletion_requests (
          account_id,
          token_hash,
          expires_at
        )
        VALUES ($1, $2, $3)
      `,
      [accountId, sha256(confirmationToken), expiresAt],
    );
  });
  return { confirmationToken, expiresAt };
}

export async function confirmAccountDeletion(
  accountId: string,
  confirmationToken: string,
): Promise<void> {
  if (!/^[A-Za-z0-9_-]{43}$/.test(confirmationToken)) {
    throw new AccountApiError("invalid_deletion_confirmation", 400);
  }
  await withTransaction(async (client) => {
    const request = await client.query<{ request_id: string }>(
      `
        SELECT request_id
        FROM account_deletion_requests
        WHERE account_id = $1
          AND token_hash = $2
          AND consumed_at IS NULL
          AND expires_at > now()
        FOR UPDATE
      `,
      [accountId, sha256(confirmationToken)],
    );
    const requestId = request.rows[0]?.request_id;
    if (!requestId) {
      throw new AccountApiError("invalid_deletion_confirmation", 409);
    }

    await client.query(
      `
        INSERT INTO web_grant_revocations (
          session_token_hash,
          idempotency_key
        )
        SELECT token_hash, gen_random_uuid()
        FROM web_sessions
        WHERE account_id = $1 AND revoked_at IS NULL
        ON CONFLICT (session_token_hash) DO NOTHING
      `,
      [accountId],
    );
    await client.query(
      "UPDATE web_sessions SET revoked_at = now() WHERE account_id = $1 AND revoked_at IS NULL",
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
    await client.query(
      "UPDATE account_sessions SET revoked_at = now() WHERE account_id = $1 AND revoked_at IS NULL",
      [accountId],
    );
    await client.query(
      "UPDATE account_devices SET revoked_at = now() WHERE account_id = $1 AND revoked_at IS NULL",
      [accountId],
    );
    await client.query(
      "UPDATE passkey_credentials SET revoked_at = now() WHERE account_id = $1 AND revoked_at IS NULL",
      [accountId],
    );
    await client.query(
      "UPDATE recovery_codes SET revoked_at = now() WHERE account_id = $1 AND used_at IS NULL AND revoked_at IS NULL",
      [accountId],
    );
    await client.query("DELETE FROM password_credentials WHERE account_id = $1", [accountId]);
    await client.query(
      `
        UPDATE account_identities
        SET
          provider_subject = 'deleted:' || identity_id::text,
          label = 'Deleted identity',
          revoked_at = COALESCE(revoked_at, now()),
          last_used_at = NULL
        WHERE account_id = $1
      `,
      [accountId],
    );
    await client.query(
      `
        UPDATE legacy_account_links
        SET
          user_key = 'deleted:' || account_id::text,
          revoked_at = COALESCE(revoked_at, now())
        WHERE account_id = $1
      `,
      [accountId],
    );
    await client.query(
      `
        UPDATE account_entitlements
        SET
          status = CASE WHEN status = 'revoked' THEN status ELSE 'revocation_pending' END,
          external_subject = NULL,
          metadata = '{}'::jsonb,
          updated_at = now()
        WHERE account_id = $1
      `,
      [accountId],
    );
    await client.query(
      `
        UPDATE support_tickets
        SET subject = 'Deleted account request', updated_at = now()
        WHERE account_id = $1
      `,
      [accountId],
    );
    await client.query(
      `
        UPDATE support_ticket_replies AS reply
        SET body = '[redacted after account deletion]', diagnostic_metadata = '{}'::jsonb
        FROM support_tickets AS ticket
        WHERE reply.ticket_id = ticket.ticket_id AND ticket.account_id = $1
      `,
      [accountId],
    );
    await client.query(
      `
        UPDATE account_deletion_requests
        SET consumed_at = now()
        WHERE request_id = $1
      `,
      [requestId],
    );
    await client.query(
      "DELETE FROM account_legacy_link_reservations WHERE account_id = $1",
      [accountId],
    );
    const deleted = await client.query(
      `
        UPDATE accounts
        SET
          display_name = 'Deleted account',
          status = 'deleted',
          security_metadata = '{}'::jsonb,
          deleted_at = now(),
          updated_at = now()
        WHERE account_id = $1 AND status = 'deletion_pending'
      `,
      [accountId],
    );
    if (deleted.rowCount !== 1) {
      throw new AccountApiError("invalid_deletion_confirmation", 409);
    }
    await client.query(
      `
        INSERT INTO web_audit_events (
          correlation_id,
          account_id,
          event_type,
          outcome,
          metadata
        )
        VALUES (
          gen_random_uuid(),
          $1,
          'account.deletion.confirm',
          'success',
          '{"action":"anonymize"}'::jsonb
        )
      `,
      [accountId],
    );
  });
}
