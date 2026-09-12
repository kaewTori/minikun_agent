ALTER TABLE minikun_background_chat_job
    ADD COLUMN IF NOT EXISTS image_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUESTED';

ALTER TABLE minikun_background_chat_job
    ADD COLUMN IF NOT EXISTS image_error VARCHAR(1000) NOT NULL DEFAULT '';

ALTER TABLE minikun_background_chat_job
    ADD COLUMN IF NOT EXISTS image_updated_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE minikun_background_chat_job
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(200) NOT NULL DEFAULT '';

ALTER TABLE minikun_background_chat_job
    ADD COLUMN IF NOT EXISTS owner_id VARCHAR(200) NOT NULL DEFAULT '';

ALTER TABLE minikun_background_chat_job
    ADD COLUMN IF NOT EXISTS deadline_at TIMESTAMP WITH TIME ZONE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_minikun_background_chat_job_idempotency
    ON minikun_background_chat_job(owner_id, conversation_id, idempotency_key)
    WHERE idempotency_key <> '';

CREATE INDEX IF NOT EXISTS idx_minikun_background_chat_job_image_pending
    ON minikun_background_chat_job(image_status, created_at);
