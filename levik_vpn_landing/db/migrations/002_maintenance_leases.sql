ALTER TABLE web_grant_revocations
  ADD COLUMN IF NOT EXISTS lease_token uuid,
  ADD COLUMN IF NOT EXISTS lease_expires_at timestamptz;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'web_grant_revocations_lease_pair_check'
      AND conrelid = 'web_grant_revocations'::regclass
  ) THEN
    ALTER TABLE web_grant_revocations
      ADD CONSTRAINT web_grant_revocations_lease_pair_check
      CHECK (
        (lease_token IS NULL AND lease_expires_at IS NULL)
        OR
        (lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
      );
  END IF;
END
$$;

CREATE INDEX IF NOT EXISTS web_grant_revocations_claim_idx
  ON web_grant_revocations (next_attempt_at, lease_expires_at)
  WHERE completed_at IS NULL;
