CREATE TABLE IF NOT EXISTS minikun_paired_device (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(200) NOT NULL,
    name VARCHAR(120) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_minikun_paired_device_owner
    ON minikun_paired_device(owner_id, revoked_at, last_seen_at DESC);

CREATE TABLE IF NOT EXISTS minikun_chat_conversation (
    owner_id VARCHAR(200) NOT NULL,
    id VARCHAR(200) NOT NULL,
    title VARCHAR(160) NOT NULL,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (owner_id, id)
);

CREATE INDEX IF NOT EXISTS idx_minikun_chat_conversation_recent
    ON minikun_chat_conversation(owner_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_minikun_chat_conversation_active
    ON minikun_chat_conversation(owner_id, archived, pinned DESC, updated_at DESC);

CREATE TABLE IF NOT EXISTS minikun_chat_message (
    owner_id VARCHAR(200) NOT NULL,
    conversation_id VARCHAR(200) NOT NULL,
    id VARCHAR(200) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    files_json TEXT NOT NULL DEFAULT '[]',
    attachments_json TEXT NOT NULL DEFAULT '[]',
    usage_json TEXT,
    timing_json TEXT,
    metadata_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (owner_id, conversation_id, id),
    CONSTRAINT fk_minikun_chat_message_conversation
        FOREIGN KEY (owner_id, conversation_id)
        REFERENCES minikun_chat_conversation(owner_id, id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_minikun_chat_message_order
    ON minikun_chat_message(owner_id, conversation_id, created_at, id);

CREATE TABLE IF NOT EXISTS minikun_background_chat_job (
    id UUID PRIMARY KEY,
    conversation_id VARCHAR(200) NOT NULL,
    request_json TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    response_json TEXT,
    error VARCHAR(1000) NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_minikun_background_chat_job_pending
    ON minikun_background_chat_job(status, created_at);
