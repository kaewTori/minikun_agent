CREATE TABLE IF NOT EXISTS minikun_memory (
    id UUID PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL,
    category VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    confidence DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    reason TEXT NOT NULL DEFAULT 'legacy persisted memory',
    fingerprint VARCHAR(64) NOT NULL UNIQUE
);

ALTER TABLE minikun_memory
    ADD COLUMN IF NOT EXISTS confidence DOUBLE PRECISION NOT NULL DEFAULT 0.0;

ALTER TABLE minikun_memory
    ADD COLUMN IF NOT EXISTS reason TEXT NOT NULL DEFAULT 'legacy persisted memory';

CREATE INDEX IF NOT EXISTS idx_minikun_memory_conversation
    ON minikun_memory (conversation_id);
