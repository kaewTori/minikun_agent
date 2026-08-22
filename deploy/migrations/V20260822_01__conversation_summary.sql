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
