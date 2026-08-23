CREATE TABLE IF NOT EXISTS account_bridge_principals (
  account_id uuid PRIMARY KEY REFERENCES accounts(account_id) ON DELETE RESTRICT,
  bridge_user_key text NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (bridge_user_key ~ '^usr_[A-Za-z0-9_-]{20,80}$')
);

-- Reserve a Telegram identity locally before the bridge is asked to bind it.
-- The reservation is not an authentication identity and is therefore never
-- consulted by login/session code until the real legacy link is committed.
CREATE TABLE IF NOT EXISTS account_legacy_link_reservations (
  account_id uuid PRIMARY KEY REFERENCES accounts(account_id) ON DELETE RESTRICT,
  user_key text NOT NULL UNIQUE,
  state text NOT NULL DEFAULT 'pending',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (user_key ~ '^usr_[A-Za-z0-9_-]{20,80}$'),
  CHECK (state IN ('pending', 'committed'))
);

ALTER TABLE web_sessions
  ADD COLUMN IF NOT EXISTS session_kind text NOT NULL DEFAULT 'legacy';

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'web_sessions_session_kind_check'
      AND conrelid = 'web_sessions'::regclass
  ) THEN
    ALTER TABLE web_sessions
      ADD CONSTRAINT web_sessions_session_kind_check
      CHECK (session_kind IN ('legacy', 'account_bridge'));
  END IF;
END
$$;

CREATE TABLE IF NOT EXISTS account_bridge_authorizations (
  account_id uuid PRIMARY KEY REFERENCES accounts(account_id) ON DELETE RESTRICT,
  bridge_session_token_hash bytea UNIQUE
    REFERENCES web_sessions(token_hash) ON DELETE SET NULL,
  idempotency_key uuid NOT NULL UNIQUE,
  requested_legacy_user_key text,
  state text NOT NULL DEFAULT 'pending',
  lease_token uuid,
  lease_expires_at timestamptz,
  grant_expires_at timestamptz,
  last_error_code text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (
    requested_legacy_user_key IS NULL
    OR requested_legacy_user_key ~ '^usr_[A-Za-z0-9_-]{20,80}$'
  ),
  CHECK (state IN ('pending', 'active', 'revoked')),
  CHECK (octet_length(bridge_session_token_hash) = 32),
  CHECK (last_error_code IS NULL OR char_length(last_error_code) BETWEEN 1 AND 80),
  CHECK (
    (lease_token IS NULL AND lease_expires_at IS NULL)
    OR (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
  ),
  CHECK (
    state <> 'active'
    OR (
      bridge_session_token_hash IS NOT NULL
      AND grant_expires_at IS NOT NULL
      AND lease_token IS NULL
      AND lease_expires_at IS NULL
    )
  )
);

CREATE INDEX IF NOT EXISTS account_bridge_authorizations_refresh_idx
  ON account_bridge_authorizations (state, lease_expires_at, grant_expires_at);

CREATE UNIQUE INDEX IF NOT EXISTS account_bridge_authorizations_legacy_key_idx
  ON account_bridge_authorizations (requested_legacy_user_key)
  WHERE requested_legacy_user_key IS NOT NULL
    AND state IN ('pending', 'active');

-- A pre-release implementation temporarily stored synthetic bridge principals
-- as Telegram legacy links. A real Telegram link always has a matching active
-- Telegram identity, so retain those and move only synthetic-only rows.
INSERT INTO account_bridge_principals (account_id, bridge_user_key)
SELECT link.account_id, link.user_key
FROM legacy_account_links AS link
WHERE link.revoked_at IS NULL
  AND NOT EXISTS (
    SELECT 1
    FROM account_identities AS identity
    WHERE identity.account_id = link.account_id
      AND identity.provider = 'telegram'
      AND identity.provider_subject = link.user_key
      AND identity.revoked_at IS NULL
  )
ON CONFLICT (account_id) DO NOTHING;

DELETE FROM legacy_account_links AS link
USING account_bridge_principals AS principal
WHERE link.account_id = principal.account_id
  AND link.user_key = principal.bridge_user_key
  AND link.revoked_at IS NULL
  AND NOT EXISTS (
    SELECT 1
    FROM account_identities AS identity
    WHERE identity.account_id = link.account_id
      AND identity.provider = 'telegram'
      AND identity.provider_subject = link.user_key
      AND identity.revoked_at IS NULL
  );

-- Only real Telegram links remain after synthetic placeholders have moved to
-- account_bridge_principals, so they are safe to mark as committed owners.
INSERT INTO account_legacy_link_reservations (account_id, user_key, state)
SELECT account_id, user_key, 'committed'
FROM legacy_account_links
WHERE revoked_at IS NULL
ON CONFLICT DO NOTHING;

-- Retire grant/link placeholders: a bridge grant is authorization, not an
-- entitlement. Runtime synchronization creates rows only for real bridge
-- subscriptions returned by the ownership-checked account snapshot.
UPDATE account_entitlements
SET
  status = 'revoked',
  external_subject = NULL,
  metadata = '{"mode":"retired_external_reference"}'::jsonb,
  updated_at = now()
WHERE metadata @> '{"mode":"external_reference"}'::jsonb;
