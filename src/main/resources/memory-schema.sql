CREATE TABLE IF NOT EXISTS minikun_memory (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255),
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

ALTER TABLE minikun_memory
    ADD COLUMN IF NOT EXISTS owner_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_minikun_memory_conversation
    ON minikun_memory (owner_id, conversation_id, created_at, id);

CREATE TABLE IF NOT EXISTS minikun_conversation_summary (
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    covered_fingerprints TEXT NOT NULL DEFAULT '',
    summarized_messages INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_id, conversation_id)
);

CREATE INDEX IF NOT EXISTS idx_minikun_conversation_summary_updated
    ON minikun_conversation_summary (owner_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS minikun_user_profile (
    owner_id VARCHAR(255) PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL DEFAULT '',
    preferred_language VARCHAR(64) NOT NULL DEFAULT '',
    response_style VARCHAR(255) NOT NULL DEFAULT '',
    timezone VARCHAR(128) NOT NULL DEFAULT '',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS minikun_user_preference (
    owner_id VARCHAR(255) NOT NULL,
    preference_key VARCHAR(255) NOT NULL,
    preference_value TEXT NOT NULL,
    confidence DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_id, preference_key)
);

CREATE TABLE IF NOT EXISTS minikun_adaptation_signal (
    owner_id VARCHAR(255) NOT NULL,
    dimension VARCHAR(64) NOT NULL,
    candidate_value VARCHAR(64) NOT NULL,
    observations INTEGER NOT NULL DEFAULT 0,
    explicit_observations INTEGER NOT NULL DEFAULT 0,
    score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_id, dimension, candidate_value)
);

CREATE INDEX IF NOT EXISTS idx_minikun_adaptation_owner_updated
    ON minikun_adaptation_signal (owner_id, updated_at DESC);
