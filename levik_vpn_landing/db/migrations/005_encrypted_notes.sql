CREATE TABLE IF NOT EXISTS encrypted_notes (
  id text PRIMARY KEY,
  key_commitment bytea NOT NULL,
  iv bytea NOT NULL,
  ciphertext bytea NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  CHECK (id ~ '^[A-Za-z0-9_-]{22}$'),
  CHECK (octet_length(key_commitment) = 32),
  CHECK (octet_length(iv) = 12),
  CHECK (octet_length(ciphertext) BETWEEN 17 AND 12016),
  CHECK (expires_at > created_at),
  CHECK (expires_at <= created_at + interval '30 days 1 minute')
);

CREATE INDEX IF NOT EXISTS encrypted_notes_expiry_idx
  ON encrypted_notes (expires_at);
