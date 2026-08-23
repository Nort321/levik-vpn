import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import pg from "pg";

const { Client } = pg;

const required = (name) => {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
};

const appRole = process.env.APP_DATABASE_ROLE ?? "levik_app";
if (!/^[a-z][a-z0-9_]{0,62}$/.test(appRole)) {
  throw new Error("APP_DATABASE_ROLE is invalid");
}

const quoteIdentifier = (value) => `"${value.replaceAll('"', '""')}"`;
const role = quoteIdentifier(appRole);
const migrationsDirectory = path.resolve("db/migrations");
const files = (await readdir(migrationsDirectory))
  .filter((file) => /^\d{3}_[a-z0-9_]+\.sql$/.test(file))
  .sort();

const client = new Client({
  host: required("DB_HOST"),
  port: Number(process.env.DB_PORT ?? "5432"),
  database: process.env.DB_NAME ?? required("POSTGRES_DB"),
  user: process.env.DB_USER ?? required("POSTGRES_USER"),
  password: process.env.DB_PASSWORD ?? required("POSTGRES_PASSWORD"),
  ssl: process.env.DB_SSL === "true" ? { rejectUnauthorized: true } : false,
});

await client.connect();

try {
  await client.query("SELECT pg_advisory_lock($1)", [731946205]);
  await client.query(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      version text PRIMARY KEY,
      applied_at timestamptz NOT NULL DEFAULT now()
    )
  `);

  for (const file of files) {
    const alreadyApplied = await client.query(
      "SELECT 1 FROM schema_migrations WHERE version = $1",
      [file],
    );
    if (alreadyApplied.rowCount) {
      continue;
    }

    const sql = await readFile(path.join(migrationsDirectory, file), "utf8");
    await client.query("BEGIN");
    try {
      await client.query(sql);
      await client.query(
        "INSERT INTO schema_migrations (version) VALUES ($1)",
        [file],
      );
      await client.query("COMMIT");
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    }
  }

  await client.query("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
  await client.query(`REVOKE ALL PRIVILEGES ON SCHEMA public FROM ${role}`);
  await client.query(
    `REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM ${role}`,
  );
  await client.query(
    `REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM ${role}`,
  );
  await client.query(
    `ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL PRIVILEGES ON TABLES FROM ${role}`,
  );
  await client.query(
    `ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL PRIVILEGES ON SEQUENCES FROM ${role}`,
  );
  await client.query(`GRANT USAGE ON SCHEMA public TO ${role}`);
  await client.query(
    `
      GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
        public.web_login_attempts,
        public.web_sessions,
        public.web_rate_limits,
        public.web_grant_revocations,
        public.web_ephemeral_credentials,
        public.encrypted_notes,
        public.ip_rdap_cache,
        public.mobile_login_bindings,
        public.mobile_registration_nonces,
        public.mobile_session_bindings,
        public.mobile_login_nonces,
        public.mobile_session_nonces,
        public.accounts,
        public.account_identities,
        public.password_credentials,
        public.passkey_credentials,
        public.webauthn_challenges,
        public.account_auth_challenges,
        public.recovery_codes,
        public.legacy_account_links,
        public.account_entitlements,
        public.account_sessions,
        public.account_devices,
        public.account_activations,
        public.support_tickets,
        public.support_ticket_replies,
        public.account_deletion_requests,
        public.account_bridge_principals,
        public.account_bridge_authorizations,
        public.account_legacy_link_reservations,
        public.monitor_probes,
        public.monitor_batches,
        public.monitor_measurements,
        public.monitor_browser_checks,
        public.app_updates
      TO ${role}
    `,
  );
  await client.query(
    `GRANT INSERT ON TABLE public.web_audit_events TO ${role}`,
  );
  await client.query(
    `
      GRANT USAGE ON SEQUENCE
        public.web_audit_events_id_seq,
        public.web_grant_revocations_id_seq
      TO ${role}
    `,
  );
} finally {
  await client.query("SELECT pg_advisory_unlock($1)", [731946205]).catch(() => {});
  await client.end();
}
