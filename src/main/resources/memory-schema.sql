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

CREATE TABLE IF NOT EXISTS minikun_conversation_thread (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    source_conversation_id VARCHAR(255) NOT NULL,
    topic VARCHAR(240) NOT NULL,
    topic_fingerprint VARCHAR(64) NOT NULL,
    summary TEXT NOT NULL DEFAULT '',
    last_decision TEXT NOT NULL DEFAULT '',
    unresolved_question TEXT NOT NULL DEFAULT '',
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    check_in_at TIMESTAMP WITH TIME ZONE,
    check_in_consent BOOLEAN NOT NULL DEFAULT FALSE,
    last_check_in_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_minikun_thread_check_in_consent
        CHECK (check_in_at IS NULL OR check_in_consent = TRUE)
);

CREATE INDEX IF NOT EXISTS idx_minikun_conversation_thread_owner
    ON minikun_conversation_thread (owner_id, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_minikun_conversation_thread_topic
    ON minikun_conversation_thread (owner_id, topic_fingerprint, status);

CREATE INDEX IF NOT EXISTS idx_minikun_conversation_thread_check_in
    ON minikun_conversation_thread (check_in_at)
    WHERE status = 'OPEN' AND check_in_consent = TRUE;

CREATE TABLE IF NOT EXISTS minikun_chat_feedback (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    message_id VARCHAR(255) NOT NULL,
    rating VARCHAR(16) NOT NULL,
    category VARCHAR(48) NOT NULL DEFAULT 'OTHER',
    reason VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_id, conversation_id, message_id)
);

CREATE INDEX IF NOT EXISTS idx_minikun_chat_feedback_owner
    ON minikun_chat_feedback (owner_id, created_at DESC);

CREATE TABLE IF NOT EXISTS minikun_companion_mode (
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    mode VARCHAR(24) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_id, conversation_id)
);

CREATE TABLE IF NOT EXISTS minikun_inspiration_board (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    title VARCHAR(120) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_minikun_inspiration_board_owner
    ON minikun_inspiration_board (owner_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS minikun_inspiration_board_item (
    id UUID PRIMARY KEY,
    board_id UUID NOT NULL REFERENCES minikun_inspiration_board(id) ON DELETE CASCADE,
    image_url VARCHAR(4000) NOT NULL,
    source_url VARCHAR(4000) NOT NULL DEFAULT '',
    title VARCHAR(240) NOT NULL DEFAULT '',
    description VARCHAR(1000) NOT NULL DEFAULT '',
    origin VARCHAR(24) NOT NULL DEFAULT 'web',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_minikun_inspiration_board_item_board
    ON minikun_inspiration_board_item (board_id, created_at DESC);

CREATE TABLE IF NOT EXISTS minikun_character_visual_memory (
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    profile_json TEXT NOT NULL DEFAULT '[]',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_id, conversation_id)
);

CREATE INDEX IF NOT EXISTS idx_minikun_character_visual_memory_updated
    ON minikun_character_visual_memory (owner_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS minikun_image_generation_history (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    origin VARCHAR(32) NOT NULL DEFAULT 'generated',
    illustration_mode VARCHAR(32) NOT NULL DEFAULT '',
    scene_title VARCHAR(240) NOT NULL DEFAULT '',
    prompt TEXT NOT NULL,
    negative_prompt TEXT NOT NULL DEFAULT '',
    seed BIGINT NOT NULL,
    provider VARCHAR(255) NOT NULL DEFAULT '',
    width INTEGER,
    height INTEGER,
    steps INTEGER,
    image_url VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_minikun_image_generation_history_scope
    ON minikun_image_generation_history (owner_id, conversation_id, created_at DESC);
