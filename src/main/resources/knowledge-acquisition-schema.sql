CREATE TABLE IF NOT EXISTS minikun_knowledge_topic (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    name VARCHAR(200) NOT NULL,
    objective TEXT NOT NULL,
    origin VARCHAR(32) NOT NULL,
    priority INTEGER NOT NULL,
    refresh_policy VARCHAR(32) NOT NULL,
    source_policy VARCHAR(32) NOT NULL,
    trusted_domains TEXT NOT NULL DEFAULT '[]',
    status VARCHAR(32) NOT NULL,
    next_run_at TIMESTAMP WITH TIME ZONE,
    last_run_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (owner_id, name),
    CHECK (priority BETWEEN 0 AND 100)
);

CREATE INDEX IF NOT EXISTS idx_minikun_knowledge_topic_due
    ON minikun_knowledge_topic (owner_id, status, next_run_at, priority DESC);

CREATE TABLE IF NOT EXISTS minikun_knowledge_acquisition_run (
    id UUID PRIMARY KEY,
    topic_id UUID NOT NULL REFERENCES minikun_knowledge_topic(id) ON DELETE CASCADE,
    owner_id VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    trigger_type VARCHAR(32) NOT NULL,
    objective TEXT NOT NULL,
    trace TEXT NOT NULL DEFAULT '',
    stop_reason VARCHAR(100) NOT NULL DEFAULT '',
    source_count INTEGER NOT NULL DEFAULT 0,
    candidate_count INTEGER NOT NULL DEFAULT 0,
    published_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT NOT NULL DEFAULT '',
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_minikun_knowledge_acquisition_run_owner
    ON minikun_knowledge_acquisition_run (owner_id, started_at DESC);

CREATE TABLE IF NOT EXISTS minikun_external_source (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES minikun_knowledge_acquisition_run(id) ON DELETE CASCADE,
    topic_id UUID NOT NULL REFERENCES minikun_knowledge_topic(id) ON DELETE CASCADE,
    owner_id VARCHAR(255) NOT NULL,
    source_url TEXT NOT NULL,
    source_kind VARCHAR(32) NOT NULL,
    content_excerpt TEXT NOT NULL DEFAULT '',
    content_hash VARCHAR(128) NOT NULL,
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (topic_id, source_url, content_hash)
);

CREATE INDEX IF NOT EXISTS idx_minikun_external_source_topic
    ON minikun_external_source (topic_id, fetched_at DESC);

CREATE TABLE IF NOT EXISTS minikun_knowledge_claim (
    id UUID PRIMARY KEY,
    topic_id UUID NOT NULL REFERENCES minikun_knowledge_topic(id) ON DELETE CASCADE,
    owner_id VARCHAR(255) NOT NULL,
    topic_name VARCHAR(200) NOT NULL,
    claim_text TEXT NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    evidence_urls TEXT NOT NULL DEFAULT '[]',
    verification_reason TEXT NOT NULL DEFAULT '',
    embedding TEXT NOT NULL DEFAULT '',
    embedding_model VARCHAR(255) NOT NULL DEFAULT '',
    discovered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    verified_at TIMESTAMP WITH TIME ZONE,
    published_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (topic_id, fingerprint),
    CHECK (confidence >= 0 AND confidence <= 1)
);

CREATE INDEX IF NOT EXISTS idx_minikun_knowledge_claim_owner
    ON minikun_knowledge_claim (owner_id, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_minikun_knowledge_claim_expiry
    ON minikun_knowledge_claim (expires_at)
    WHERE status = 'PUBLISHED';
