CREATE TABLE IF NOT EXISTS ip_rdap_cache (
  network cidr PRIMARY KEY,
  registry text NOT NULL,
  network_name text,
  network_handle text,
  network_type text,
  country_code text,
  range_start inet NOT NULL,
  range_end inet NOT NULL,
  registered_at timestamptz,
  updated_at timestamptz,
  fetched_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  CHECK (char_length(registry) BETWEEN 2 AND 40),
  CHECK (network_name IS NULL OR char_length(network_name) <= 240),
  CHECK (network_handle IS NULL OR char_length(network_handle) <= 160),
  CHECK (network_type IS NULL OR char_length(network_type) <= 80),
  CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{2}$'),
  CHECK (family(range_start) = family(range_end)),
  CHECK (range_start <= range_end),
  CHECK (expires_at > fetched_at)
);

CREATE INDEX IF NOT EXISTS ip_rdap_cache_network_idx
  ON ip_rdap_cache USING gist (network inet_ops);

CREATE INDEX IF NOT EXISTS ip_rdap_cache_expiry_idx
  ON ip_rdap_cache (expires_at);
