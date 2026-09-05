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
