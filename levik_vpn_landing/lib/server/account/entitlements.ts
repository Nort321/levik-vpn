import "server-only";

import type { BridgeSnapshot } from "@/lib/server/bridge/cabinet";
import { withTransaction } from "@/lib/server/db";

function entitlementStatus(status: string): "active" | "expired" {
  return ["active", "limited"].includes(status.toLowerCase())
    ? "active"
    : "expired";
}

export async function synchronizeBridgeEntitlements(
  accountId: string,
  subscriptions: BridgeSnapshot["subscriptions"],
): Promise<void> {
  await withTransaction(async (client) => {
    for (const subscription of subscriptions) {
      const result = await client.query(
        `
          INSERT INTO account_entitlements (
            account_id,
            source,
            external_subject,
            status,
            expires_at,
            metadata
          )
          VALUES ($1, 'bridge', $2, $3, $4, '{"mode":"subscription"}'::jsonb)
          ON CONFLICT (source, external_subject) WHERE external_subject IS NOT NULL
          DO UPDATE SET
            status = EXCLUDED.status,
            expires_at = EXCLUDED.expires_at,
            metadata = EXCLUDED.metadata,
            updated_at = now()
          WHERE account_entitlements.account_id = EXCLUDED.account_id
          RETURNING account_id
        `,
        [
          accountId,
          subscription.uuid,
          entitlementStatus(subscription.status),
          subscription.expireAt ? new Date(subscription.expireAt) : null,
        ],
      );
      if (result.rowCount !== 1) {
        throw new Error("Bridge entitlement belongs to another account");
      }
    }

    await client.query(
      `
        UPDATE account_entitlements
        SET status = 'expired', updated_at = now()
        WHERE account_id = $1
          AND source = 'bridge'
          AND metadata @> '{"mode":"subscription"}'::jsonb
          AND NOT (external_subject = ANY($2::text[]))
      `,
      [accountId, subscriptions.map((subscription) => subscription.uuid)],
    );
  });
}
