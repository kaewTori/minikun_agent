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
    check_in_at TIMESTAMPTZ,
    check_in_consent BOOLEAN NOT NULL DEFAULT FALSE,
    last_check_in_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
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
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_id, conversation_id, message_id)
);

CREATE INDEX IF NOT EXISTS idx_minikun_chat_feedback_owner
    ON minikun_chat_feedback (owner_id, created_at DESC);

CREATE TABLE IF NOT EXISTS minikun_companion_mode (
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    mode VARCHAR(24) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_id, conversation_id)
);
