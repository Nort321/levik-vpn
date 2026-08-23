CREATE TABLE IF NOT EXISTS accounts (
  account_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  levik_id text NOT NULL UNIQUE,
  display_name text NOT NULL,
  status text NOT NULL DEFAULT 'active',
  security_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  deletion_requested_at timestamptz,
  deleted_at timestamptz,
  CHECK (
    levik_id ~ '^LVK-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$'
  ),
  CHECK (char_length(display_name) BETWEEN 1 AND 120),
  CHECK (status IN ('active', 'deletion_pending', 'suspended', 'deleted')),
  CHECK (jsonb_typeof(security_metadata) = 'object'),
  CHECK (deleted_at IS NULL OR status = 'deleted')
);

CREATE INDEX IF NOT EXISTS accounts_status_idx
  ON accounts (status, created_at DESC);

CREATE TABLE IF NOT EXISTS account_identities (
  identity_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id uuid NOT NULL REFERENCES accounts(account_id) ON DELETE RESTRICT,
  provider text NOT NULL,
  provider_subject text NOT NULL,
  label text NOT NULL,
  verified_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  last_used_at timestamptz,
  revoked_at timestamptz,
  UNIQUE (provider, provider_subject),
  CHECK (provider IN ('google', 'telegram', 'password')),
  CHECK (char_length(provider_subject) BETWEEN 1 AND 512),
  CHECK (char_length(label) BETWEEN 1 AND 120)
);

CREATE UNIQUE INDEX IF NOT EXISTS account_identities_active_provider_idx
  ON account_identities (account_id, provider)
  WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS account_identities_account_idx
  ON account_identities (account_id, created_at);

CREATE TABLE IF NOT EXISTS password_credentials (
  account_id uuid PRIMARY KEY REFERENCES accounts(account_id) ON DELETE RESTRICT,
  salt bytea NOT NULL,
  derived_key bytea NOT NULL,
  algorithm text NOT NULL DEFAULT 'scrypt-v1',
  parameters jsonb NOT NULL,
  failed_attempts integer NOT NULL DEFAULT 0,
  locked_until timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (octet_length(salt) = 16),
  CHECK (octet_length(derived_key) = 32),
  CHECK (algorithm = 'scrypt-v1'),
  CHECK (jsonb_typeof(parameters) = 'object'),
  CHECK (failed_attempts BETWEEN 0 AND 1000000)
);

CREATE TABLE IF NOT EXISTS passkey_credentials (
  credential_id text PRIMARY KEY,
  account_id uuid NOT NULL REFERENCES accounts(account_id) ON DELETE RESTRICT,
  public_key bytea NOT NULL,
  signature_counter bigint NOT NULL DEFAULT 0,
  transports text[] NOT NULL DEFAULT ARRAY[]::text[],
  device_type text NOT NULL,
  backed_up boolean NOT NULL DEFAULT false,
  name text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_used_at timestamptz,
  revoked_at timestamptz,
  CHECK (credential_id ~ '^[A-Za-z0-9_-]{1,1024}$'),
  CHECK (octet_length(public_key) BETWEEN 32 AND 4096),
  CHECK (signature_counter BETWEEN 0 AND 4294967295),
  CHECK (device_type IN ('singleDevice', 'multiDevice')),
  CHECK (char_length(name) BETWEEN 1 AND 120),
  CHECK (
    transports <@ ARRAY[
      'ble', 'cable', 'hybrid', 'internal', 'nfc', 'smart-card', 'usb'
    ]::text[]
  )
);

CREATE INDEX IF NOT EXISTS passkey_credentials_account_idx
  ON passkey_credentials (account_id, created_at DESC)
  WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS webauthn_challenges (
  ceremony_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
  challenge_hash bytea PRIMARY KEY,
  account_id uuid REFERENCES accounts(account_id) ON DELETE CASCADE,
  flow text NOT NULL,
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (octet_length(challenge_hash) = 32),
  CHECK (flow IN ('authentication', 'registration'))
);

CREATE INDEX IF NOT EXISTS webauthn_challenges_expiry_idx
  ON webauthn_challenges (expires_at)
  WHERE consumed_at IS NULL;

CREATE TABLE IF NOT EXISTS account_auth_challenges (
  challenge_hash bytea PRIMARY KEY,
  purpose text NOT NULL,
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (octet_length(challenge_hash) = 32),
  CHECK (purpose IN ('google'))
);

CREATE INDEX IF NOT EXISTS account_auth_challenges_expiry_idx
  ON account_auth_challenges (expires_at)
  WHERE consumed_at IS NULL;

CREATE TABLE IF NOT EXISTS recovery_codes (
  recovery_code_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  batch_id uuid NOT NULL,
  account_id uuid NOT NULL REFERENCES accounts(account_id) ON DELETE RESTRICT,
  code_hash bytea NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  used_at timestamptz,
  revoked_at timestamptz,
  UNIQUE (account_id, code_hash),
  CHECK (octet_length(code_hash) = 32)
);

CREATE INDEX IF NOT EXISTS recovery_codes_active_idx
  ON recovery_codes (account_id, created_at DESC)
  WHERE used_at IS NULL AND revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS legacy_account_links (
  account_id uuid NOT NULL UNIQUE REFERENCES accounts(account_id) ON DELETE RESTRICT,
  user_key text NOT NULL UNIQUE,
  linked_at timestamptz NOT NULL DEFAULT now(),
  revoked_at timestamptz,
  PRIMARY KEY (account_id, user_key),
  CHECK (char_length(user_key) BETWEEN 16 AND 160)
);

CREATE TABLE IF NOT EXISTS account_entitlements (
  entitlement_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id uuid NOT NULL REFERENCES accounts(account_id) ON DELETE RESTRICT,
  source text NOT NULL,
  external_subject text,
  status text NOT NULL DEFAULT 'active',
  starts_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (source IN ('bridge')),
  CHECK (
    external_subject IS NULL OR char_length(external_subject) BETWEEN 16 AND 160
  ),
  CHECK (status IN ('active', 'expired', 'revocation_pending', 'revoked')),
  CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE UNIQUE INDEX IF NOT EXISTS account_entitlements_external_idx
  ON account_entitlements (source, external_subject)
  WHERE external_subject IS NOT NULL;

CREATE INDEX IF NOT EXISTS account_entitlements_account_idx
  ON account_entitlements (account_id, status, expires_at);

CREATE TABLE IF NOT EXISTS account_sessions (
  token_hash bytea PRIMARY KEY,
  public_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
  account_id uuid NOT NULL REFERENCES accounts(account_id) ON DELETE RESTRICT,
  legacy_session_token_hash bytea UNIQUE
    REFERENCES web_sessions(token_hash) ON DELETE SET NULL,
  auth_method text NOT NULL,
  device_name text NOT NULL,
  last_ip_key text,
  authenticated_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  idle_expires_at timestamptz NOT NULL,
  absolute_expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  CHECK (octet_length(token_hash) = 32),
  CHECK (auth_method IN ('google', 'passkey', 'password', 'recovery', 'telegram')),
  CHECK (char_length(device_name) BETWEEN 1 AND 120),
  CHECK (last_ip_key IS NULL OR char_length(last_ip_key) BETWEEN 16 AND 160),
  CHECK (idle_expires_at <= absolute_expires_at)
);

CREATE INDEX IF NOT EXISTS account_sessions_active_account_idx
  ON account_sessions (account_id, last_seen_at DESC)
  WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS account_sessions_expiry_idx
  ON account_sessions (absolute_expires_at)
  WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS account_devices (
  device_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id uuid NOT NULL REFERENCES accounts(account_id) ON DELETE RESTRICT,
  external_device_id text NOT NULL,
  name text NOT NULL,
  platform text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  revoked_at timestamptz,
  UNIQUE (account_id, external_device_id),
  CHECK (char_length(external_device_id) BETWEEN 16 AND 160),
  CHECK (char_length(name) BETWEEN 1 AND 120),
  CHECK (platform IN ('android', 'browser', 'unknown'))
);

CREATE INDEX IF NOT EXISTS account_devices_active_account_idx
  ON account_devices (account_id, last_seen_at DESC)
  WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS account_activations (
  activation_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  login_token_hash bytea NOT NULL UNIQUE
    REFERENCES web_login_attempts(token_hash) ON DELETE CASCADE,
  code_hash bytea NOT NULL UNIQUE,
  account_id uuid REFERENCES accounts(account_id) ON DELETE RESTRICT,
  expires_at timestamptz NOT NULL,
  authorized_at timestamptz,
  consumed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (octet_length(login_token_hash) = 32),
  CHECK (octet_length(code_hash) = 32),
  CHECK ((account_id IS NULL) = (authorized_at IS NULL))
);

CREATE INDEX IF NOT EXISTS account_activations_expiry_idx
  ON account_activations (expires_at)
  WHERE consumed_at IS NULL;

CREATE TABLE IF NOT EXISTS support_tickets (
  ticket_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  public_reference text NOT NULL UNIQUE,
  account_id uuid NOT NULL REFERENCES accounts(account_id) ON DELETE RESTRICT,
  category text NOT NULL,
  subject text NOT NULL,
  status text NOT NULL DEFAULT 'open',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  closed_at timestamptz,
  CHECK (public_reference ~ '^SUP-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$'),
  CHECK (category IN ('account', 'connection', 'subscription', 'privacy', 'other')),
  CHECK (char_length(subject) BETWEEN 3 AND 160),
  CHECK (status IN ('open', 'waiting_for_support', 'waiting_for_user', 'closed'))
);

CREATE INDEX IF NOT EXISTS support_tickets_account_idx
  ON support_tickets (account_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS support_ticket_replies (
  reply_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  ticket_id uuid NOT NULL REFERENCES support_tickets(ticket_id) ON DELETE CASCADE,
  author_type text NOT NULL,
  body text NOT NULL,
  diagnostic_metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (author_type IN ('account', 'support', 'system')),
  CHECK (char_length(body) BETWEEN 1 AND 8000),
  CHECK (jsonb_typeof(diagnostic_metadata) = 'object')
);

CREATE INDEX IF NOT EXISTS support_ticket_replies_ticket_idx
  ON support_ticket_replies (ticket_id, created_at);

CREATE TABLE IF NOT EXISTS account_deletion_requests (
  request_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  account_id uuid NOT NULL REFERENCES accounts(account_id) ON DELETE RESTRICT,
  token_hash bytea NOT NULL UNIQUE,
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (octet_length(token_hash) = 32)
);

CREATE INDEX IF NOT EXISTS account_deletion_requests_active_idx
  ON account_deletion_requests (account_id, expires_at DESC)
  WHERE consumed_at IS NULL;

ALTER TABLE web_sessions
  ADD COLUMN IF NOT EXISTS account_id uuid REFERENCES accounts(account_id) ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS web_sessions_active_account_idx
  ON web_sessions (account_id, created_at DESC)
  WHERE account_id IS NOT NULL AND revoked_at IS NULL;

ALTER TABLE web_audit_events
  ADD COLUMN IF NOT EXISTS account_id uuid REFERENCES accounts(account_id) ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS web_audit_events_account_idx
  ON web_audit_events (account_id, occurred_at DESC)
  WHERE account_id IS NOT NULL;

ALTER TABLE web_login_attempts
  ADD COLUMN IF NOT EXISTS provider_mode text NOT NULL DEFAULT 'legacy_bridge';

ALTER TABLE web_login_attempts
  ALTER COLUMN bridge_challenge_id DROP NOT NULL,
  ALTER COLUMN bridge_poll_secret_ciphertext DROP NOT NULL,
  ALTER COLUMN deep_link_ciphertext DROP NOT NULL,
  ALTER COLUMN verification_code DROP NOT NULL;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'web_login_attempts_provider_mode_check'
      AND conrelid = 'web_login_attempts'::regclass
  ) THEN
    ALTER TABLE web_login_attempts
      ADD CONSTRAINT web_login_attempts_provider_mode_check
      CHECK (
        (
          provider_mode = 'legacy_bridge'
          AND bridge_challenge_id IS NOT NULL
          AND bridge_poll_secret_ciphertext IS NOT NULL
          AND deep_link_ciphertext IS NOT NULL
          AND verification_code IS NOT NULL
        )
        OR
        (
          provider_mode = 'account_local'
          AND bridge_challenge_id IS NULL
          AND bridge_poll_secret_ciphertext IS NULL
          AND deep_link_ciphertext IS NULL
          AND verification_code IS NULL
        )
      );
  END IF;
END
$$;
