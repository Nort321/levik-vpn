import "server-only";

import { randomUUID } from "node:crypto";

import type { PoolClient } from "pg";

import { query } from "@/lib/server/db";

type AuditOutcome = "success" | "denied" | "error";

export type AuditEvent = {
  eventType: string;
  outcome: AuditOutcome;
  accountId?: string;
  userKey?: string;
  correlationId?: string;
  metadata?: Readonly<Record<string, string | number | boolean | null>>;
};

const SAFE_METADATA_KEYS = new Set([
  "action",
  "reason",
  "resourceType",
  "rateLimited",
  "bridgeCode",
  "identityProvider",
  "authMethod",
  "ticketStatus",
]);

function auditValues({
  eventType,
  outcome,
  accountId,
  userKey,
  correlationId = randomUUID(),
  metadata = {},
}: AuditEvent): unknown[] {
  if (!/^[a-z][a-z0-9_.-]{0,79}$/.test(eventType)) {
    throw new Error("Invalid audit event type");
  }

  const safeMetadata = Object.fromEntries(
    Object.entries(metadata).filter(([key]) => SAFE_METADATA_KEYS.has(key)),
  );

  return [
    correlationId,
    accountId ?? null,
    userKey ?? null,
    eventType,
    outcome,
    JSON.stringify(safeMetadata),
  ];
}

const INSERT_AUDIT_EVENT_SQL = `
  INSERT INTO web_audit_events (
    correlation_id,
    account_id,
    user_key,
    event_type,
    outcome,
    metadata
  )
  VALUES ($1, $2, $3, $4, $5, $6::jsonb)
`;

export async function writeAuditEvent(event: AuditEvent): Promise<void> {
  await query(INSERT_AUDIT_EVENT_SQL, auditValues(event));
}

export async function writeAuditEventWithClient(
  client: PoolClient,
  event: AuditEvent,
): Promise<void> {
  await client.query(
    INSERT_AUDIT_EVENT_SQL,
    auditValues(event),
  );
}
