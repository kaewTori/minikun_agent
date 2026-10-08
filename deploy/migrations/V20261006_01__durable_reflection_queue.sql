CREATE TABLE IF NOT EXISTS minikun_reflection_job (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    request_id VARCHAR(255) NOT NULL,
    messages_json TEXT NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_id, conversation_id, request_id)
);

CREATE INDEX IF NOT EXISTS idx_minikun_reflection_job_due
    ON minikun_reflection_job (available_at, created_at)
    WHERE status IN ('QUEUED', 'RUNNING');
