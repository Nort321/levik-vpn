CREATE TABLE IF NOT EXISTS app_updates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    version_code integer NOT NULL UNIQUE,
    version_name text NOT NULL,
    min_supported_version_code integer NOT NULL DEFAULT 1,
    file_name text NOT NULL,
    download_url text NOT NULL,
    file_size bigint NOT NULL,
    sha256 text NOT NULL,
    title_ru text NOT NULL,
    title_en text NOT NULL,
    changelog_ru text NOT NULL,
    changelog_en text,
    force_update boolean NOT NULL DEFAULT false,
    is_active boolean NOT NULL DEFAULT true,
    created_by_user_key text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS app_updates_active_idx ON app_updates (is_active, version_code DESC);
