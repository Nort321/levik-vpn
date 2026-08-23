CREATE TABLE IF NOT EXISTS monitor_browser_checks (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  received_at timestamptz NOT NULL DEFAULT now(),
  measured_at timestamptz NOT NULL,
  mode text NOT NULL,
  service_slug text NOT NULL,
  state text NOT NULL,
  reachable_checks smallint NOT NULL,
  total_checks smallint NOT NULL,
  latency_ms integer,
  country_code text,
  region text,
  city text,
  asn bigint,
  provider text,
  checks jsonb NOT NULL,
  CHECK (mode IN ('diagnostic', 'report')),
  CHECK (service_slug ~ '^[a-z0-9][a-z0-9-]{1,39}$'),
  CHECK (state IN ('reachable', 'partial', 'unreachable')),
  CHECK (total_checks BETWEEN 1 AND 16),
  CHECK (reachable_checks BETWEEN 0 AND total_checks),
  CHECK (latency_ms IS NULL OR latency_ms BETWEEN 0 AND 120000),
  CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{2}$'),
  CHECK (region IS NULL OR char_length(region) BETWEEN 1 AND 160),
  CHECK (city IS NULL OR char_length(city) BETWEEN 1 AND 160),
  CHECK (asn IS NULL OR asn BETWEEN 1 AND 4294967295),
  CHECK (provider IS NULL OR char_length(provider) BETWEEN 1 AND 240),
  CHECK (jsonb_typeof(checks) = 'array')
);

CREATE INDEX IF NOT EXISTS monitor_browser_checks_service_time_idx
  ON monitor_browser_checks (service_slug, received_at DESC);

CREATE INDEX IF NOT EXISTS monitor_browser_checks_network_time_idx
  ON monitor_browser_checks (service_slug, asn, region, received_at DESC);

COMMENT ON TABLE monitor_browser_checks IS
  'Aggregated browser diagnostics. Client IP addresses are never stored.';
