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
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_minikun_image_generation_history_scope
    ON minikun_image_generation_history (owner_id, conversation_id, created_at DESC);
