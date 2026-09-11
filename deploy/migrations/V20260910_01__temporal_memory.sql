
-- Slots retain event time independently of when a turn was recorded.
ALTER TABLE minikun_memory ADD COLUMN IF NOT EXISTS fact_subject varchar(255);
ALTER TABLE minikun_memory ADD COLUMN IF NOT EXISTS fact_key varchar(255);
ALTER TABLE minikun_memory ADD COLUMN IF NOT EXISTS fact_value text;
ALTER TABLE minikun_memory ADD COLUMN IF NOT EXISTS valid_from timestamptz;
ALTER TABLE minikun_memory ADD COLUMN IF NOT EXISTS valid_to timestamptz;
ALTER TABLE minikun_memory ADD COLUMN IF NOT EXISTS stated_valid_to timestamptz;
ALTER TABLE minikun_memory ADD COLUMN IF NOT EXISTS recorded_at timestamptz;
ALTER TABLE minikun_memory ADD COLUMN IF NOT EXISTS evidence text;
ALTER TABLE minikun_memory ADD COLUMN IF NOT EXISTS supersedes_id uuid;
CREATE INDEX IF NOT EXISTS idx_minikun_memory_slot
    ON minikun_memory(owner_id, fact_subject, fact_key, (COALESCE(valid_from, recorded_at)), recorded_at);
