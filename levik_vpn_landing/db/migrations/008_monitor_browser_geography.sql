CREATE INDEX IF NOT EXISTS monitor_browser_checks_country_time_idx
  ON monitor_browser_checks (service_slug, country_code, received_at DESC)
  WHERE country_code IS NOT NULL;
