import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

describe("Levik Account additive migration", () => {
  const migration = readFileSync(
    new URL("../db/migrations/010_levik_accounts.sql", import.meta.url),
    "utf8",
  );

  it.each([
    "accounts",
    "account_identities",
    "password_credentials",
    "passkey_credentials",
    "webauthn_challenges",
    "recovery_codes",
    "legacy_account_links",
    "account_entitlements",
    "account_sessions",
    "account_devices",
    "account_activations",
    "support_tickets",
    "support_ticket_replies",
    "account_deletion_requests",
  ])("creates %s without dropping legacy objects", (table) => {
    expect(migration).toContain(`CREATE TABLE IF NOT EXISTS ${table}`);
    expect(migration).not.toMatch(/\bDROP\s+(?:TABLE|COLUMN|TYPE)\b/i);
  });

  it("adds ownership to legacy sessions and audit events", () => {
    expect(migration).toContain("ALTER TABLE web_sessions");
    expect(migration).toContain("ALTER TABLE web_audit_events");
    expect(migration).toContain("web_sessions_active_account_idx");
  });

  it("allows local account attempts while preserving strict legacy invariants", () => {
    expect(migration).toContain("provider_mode text NOT NULL DEFAULT 'legacy_bridge'");
    expect(migration).toContain("provider_mode = 'account_local'");
    expect(migration).toContain("bridge_poll_secret_ciphertext IS NULL");
    expect(migration).toContain("provider_mode = 'legacy_bridge'");
    expect(migration).toContain("bridge_poll_secret_ciphertext IS NOT NULL");
  });
});
