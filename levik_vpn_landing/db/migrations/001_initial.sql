CREATE TABLE IF NOT EXISTS schema_migrations (
  version text PRIMARY KEY,
  applied_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS web_login_attempts (
  token_hash bytea PRIMARY KEY,
  bridge_challenge_id text NOT NULL UNIQUE,
  bridge_poll_secret_ciphertext text NOT NULL,
  deep_link_ciphertext text NOT NULL,
  verification_code text NOT NULL,
  poll_interval_seconds integer NOT NULL,
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz,
  last_polled_at timestamptz,
  poll_lock_until timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (char_length(bridge_challenge_id) BETWEEN 16 AND 160),
  CHECK (verification_code ~ '^[A-Z0-9]{4,12}$'),
  CHECK (poll_interval_seconds BETWEEN 1 AND 10)
);

CREATE TABLE IF NOT EXISTS web_sessions (
  token_hash bytea PRIMARY KEY,
  public_id uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  user_key text NOT NULL,
  user_label text,
  device_label text NOT NULL,
  last_ip_key text,
  grant_ciphertext text NOT NULL,
  grant_expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  idle_expires_at timestamptz NOT NULL,
  absolute_expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  CHECK (char_length(user_key) BETWEEN 16 AND 160),
  CHECK (user_label IS NULL OR char_length(user_label) <= 160),
  CHECK (char_length(device_label) BETWEEN 1 AND 120),
  CHECK (last_ip_key IS NULL OR char_length(last_ip_key) BETWEEN 16 AND 160),
  CHECK (idle_expires_at <= absolute_expires_at)
);

CREATE INDEX IF NOT EXISTS web_sessions_active_user_idx
  ON web_sessions (user_key, created_at DESC)
  WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS web_sessions_expiry_idx
  ON web_sessions (absolute_expires_at)
  WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS web_rate_limits (
  key_hash bytea NOT NULL,
  bucket_start timestamptz NOT NULL,
  request_count integer NOT NULL DEFAULT 1,
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (key_hash, bucket_start),
  CHECK (request_count > 0)
);

CREATE INDEX IF NOT EXISTS web_rate_limits_expiry_idx
  ON web_rate_limits (expires_at);

CREATE TABLE IF NOT EXISTS web_audit_events (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  correlation_id uuid NOT NULL,
  user_key text,
  event_type text NOT NULL,
  outcome text NOT NULL,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  CHECK (char_length(event_type) BETWEEN 1 AND 80),
  CHECK (outcome IN ('success', 'denied', 'error'))
);

CREATE INDEX IF NOT EXISTS web_audit_events_lookup_idx
  ON web_audit_events (occurred_at DESC, event_type);

CREATE TABLE IF NOT EXISTS web_grant_revocations (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  session_token_hash bytea NOT NULL UNIQUE
    REFERENCES web_sessions(token_hash) ON DELETE RESTRICT,
  idempotency_key uuid NOT NULL UNIQUE,
  attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  last_error_code text,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (attempts >= 0),
  CHECK (
    last_error_code IS NULL OR char_length(last_error_code) BETWEEN 1 AND 80
  )
);

CREATE INDEX IF NOT EXISTS web_grant_revocations_pending_idx
  ON web_grant_revocations (next_attempt_at)
  WHERE completed_at IS NULL;

CREATE TABLE IF NOT EXISTS web_ephemeral_credentials (
  user_key text NOT NULL,
  credential_kind text NOT NULL,
  credential_ciphertext text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (user_key, credential_kind),
  CHECK (char_length(user_key) BETWEEN 16 AND 160),
  CHECK (credential_kind IN ('free_proxy'))
);

CREATE INDEX IF NOT EXISTS web_ephemeral_credentials_expiry_idx
  ON web_ephemeral_credentials (expires_at);
