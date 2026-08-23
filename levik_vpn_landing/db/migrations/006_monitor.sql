CREATE TABLE IF NOT EXISTS monitor_probes (
  id text PRIMARY KEY,
  label text NOT NULL,
  country_code text NOT NULL,
  region text,
  agent_version text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  CHECK (id ~ '^[a-z0-9][a-z0-9_-]{2,39}$'),
  CHECK (char_length(label) BETWEEN 2 AND 80),
  CHECK (country_code ~ '^[A-Z]{2}$'),
  CHECK (region IS NULL OR char_length(region) BETWEEN 2 AND 80),
  CHECK (char_length(agent_version) BETWEEN 1 AND 32)
);

CREATE TABLE IF NOT EXISTS monitor_batches (
  batch_id uuid PRIMARY KEY,
  probe_id text NOT NULL REFERENCES monitor_probes(id) ON DELETE RESTRICT,
  measured_at timestamptz NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  CHECK (measured_at <= received_at + interval '5 minutes')
);

CREATE INDEX IF NOT EXISTS monitor_batches_probe_time_idx
  ON monitor_batches (probe_id, measured_at DESC);

CREATE TABLE IF NOT EXISTS monitor_measurements (
  batch_id uuid NOT NULL REFERENCES monitor_batches(batch_id) ON DELETE CASCADE,
  service_slug text NOT NULL,
  state text NOT NULL,
  availability smallint NOT NULL,
  latency_ms integer,
  checks jsonb NOT NULL,
  PRIMARY KEY (batch_id, service_slug),
  CHECK (service_slug ~ '^[a-z0-9][a-z0-9-]{1,39}$'),
  CHECK (state IN ('operational', 'degraded', 'outage')),
  CHECK (availability BETWEEN 0 AND 100),
  CHECK (latency_ms IS NULL OR latency_ms BETWEEN 0 AND 120000),
  CHECK (jsonb_typeof(checks) = 'object')
);

CREATE INDEX IF NOT EXISTS monitor_measurements_service_idx
  ON monitor_measurements (service_slug, batch_id);
