CREATE TABLE IF NOT EXISTS mobile_login_bindings (
  login_token_hash bytea PRIMARY KEY
    REFERENCES web_login_attempts(token_hash) ON DELETE CASCADE,
  device_id text NOT NULL,
  public_key_spki bytea NOT NULL,
  device_label text NOT NULL,
  app_version text NOT NULL,
  os_version text NOT NULL,
  device_model text NOT NULL,
  request_signing_algorithm text NOT NULL,
  profile_encryption_algorithm text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (device_id ~ '^[0-9a-f]{64}$'),
  CHECK (octet_length(public_key_spki) BETWEEN 256 AND 1024),
  CHECK (char_length(device_label) BETWEEN 1 AND 120),
  CHECK (char_length(app_version) BETWEEN 1 AND 40),
  CHECK (char_length(os_version) BETWEEN 1 AND 80),
  CHECK (char_length(device_model) BETWEEN 1 AND 120),
  CHECK (request_signing_algorithm IN ('PS256', 'RS256')),
  CHECK (
    profile_encryption_algorithm IN (
      'RSA-OAEP-256+A256GCM',
      'RSA-OAEP+A256GCM'
    )
  )
);

CREATE INDEX IF NOT EXISTS mobile_login_bindings_device_idx
  ON mobile_login_bindings (device_id, created_at DESC);

CREATE TABLE IF NOT EXISTS mobile_registration_nonces (
  device_id text NOT NULL,
  nonce_hash bytea NOT NULL,
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (device_id, nonce_hash),
  CHECK (device_id ~ '^[0-9a-f]{64}$'),
  CHECK (octet_length(nonce_hash) = 32)
);

CREATE INDEX IF NOT EXISTS mobile_registration_nonces_expiry_idx
  ON mobile_registration_nonces (expires_at);

CREATE TABLE IF NOT EXISTS mobile_session_bindings (
  session_token_hash bytea PRIMARY KEY
    REFERENCES web_sessions(token_hash) ON DELETE CASCADE,
  device_id text NOT NULL,
  public_key_spki bytea NOT NULL,
  device_label text NOT NULL,
  app_version text NOT NULL,
  os_version text NOT NULL,
  device_model text NOT NULL,
  request_signing_algorithm text NOT NULL,
  profile_encryption_algorithm text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (device_id ~ '^[0-9a-f]{64}$'),
  CHECK (octet_length(public_key_spki) BETWEEN 256 AND 1024),
  CHECK (char_length(device_label) BETWEEN 1 AND 120),
  CHECK (char_length(app_version) BETWEEN 1 AND 40),
  CHECK (char_length(os_version) BETWEEN 1 AND 80),
  CHECK (char_length(device_model) BETWEEN 1 AND 120),
  CHECK (request_signing_algorithm IN ('PS256', 'RS256')),
  CHECK (
    profile_encryption_algorithm IN (
      'RSA-OAEP-256+A256GCM',
      'RSA-OAEP+A256GCM'
    )
  )
);

CREATE INDEX IF NOT EXISTS mobile_session_bindings_device_idx
  ON mobile_session_bindings (device_id, created_at DESC);

CREATE TABLE IF NOT EXISTS mobile_login_nonces (
  login_token_hash bytea NOT NULL
    REFERENCES mobile_login_bindings(login_token_hash) ON DELETE CASCADE,
  nonce_hash bytea NOT NULL,
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (login_token_hash, nonce_hash),
  CHECK (octet_length(nonce_hash) = 32)
);

CREATE INDEX IF NOT EXISTS mobile_login_nonces_expiry_idx
  ON mobile_login_nonces (expires_at);

CREATE TABLE IF NOT EXISTS mobile_session_nonces (
  session_token_hash bytea NOT NULL
    REFERENCES mobile_session_bindings(session_token_hash) ON DELETE CASCADE,
  nonce_hash bytea NOT NULL,
  expires_at timestamptz NOT NULL,
  PRIMARY KEY (session_token_hash, nonce_hash),
  CHECK (octet_length(nonce_hash) = 32)
);

CREATE INDEX IF NOT EXISTS mobile_session_nonces_expiry_idx
  ON mobile_session_nonces (expires_at);
