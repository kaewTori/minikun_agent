CREATE TABLE IF NOT EXISTS minikun_action_checkpoint (
    run_id UUID PRIMARY KEY REFERENCES minikun_agent_run(id) ON DELETE CASCADE,
    owner_id VARCHAR(255) NOT NULL,
    dedup_key VARCHAR(512),
    state_json TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    UNIQUE(owner_id, dedup_key)
);
CREATE TABLE IF NOT EXISTS minikun_action_grant (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    tool VARCHAR(100) NOT NULL,
    action VARCHAR(100) NOT NULL,
    resource VARCHAR(2048) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    max_uses INTEGER NOT NULL CHECK (max_uses > 0),
    uses INTEGER NOT NULL DEFAULT 0,
    cooldown_seconds INTEGER NOT NULL DEFAULT 0,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    monitor_component VARCHAR(100) NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_minikun_action_grant_owner ON minikun_action_grant(owner_id);
