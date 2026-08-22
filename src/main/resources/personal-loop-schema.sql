CREATE TABLE IF NOT EXISTS minikun_weekly_review (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    summary_json TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_minikun_weekly_review_period
    ON minikun_weekly_review (owner_id, period_start, period_end);

CREATE TABLE IF NOT EXISTS minikun_review_proposal (
    id UUID PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES minikun_weekly_review(id) ON DELETE CASCADE,
    owner_id VARCHAR(255) NOT NULL,
    proposal_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(255) NOT NULL DEFAULT '',
    title TEXT NOT NULL,
    reason TEXT NOT NULL DEFAULT '',
    payload_json TEXT NOT NULL DEFAULT '{}',
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_minikun_review_proposal_review
    ON minikun_review_proposal (review_id, status, created_at);

CREATE TABLE IF NOT EXISTS minikun_outcome (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    category VARCHAR(64) NOT NULL,
    recommendation TEXT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id VARCHAR(255) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL,
    result_note TEXT NOT NULL DEFAULT '',
    score INTEGER,
    created_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    evaluated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_minikun_outcome_owner
    ON minikun_outcome (owner_id, category, created_at DESC);

CREATE TABLE IF NOT EXISTS minikun_inbox_item (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    input_type VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    source_ref TEXT NOT NULL DEFAULT '',
    classification VARCHAR(32) NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    status VARCHAR(32) NOT NULL,
    preview_json TEXT NOT NULL DEFAULT '{}',
    target_type VARCHAR(32) NOT NULL DEFAULT '',
    target_id VARCHAR(255) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_minikun_inbox_owner
    ON minikun_inbox_item (owner_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS minikun_automation_recipe (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    name TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    trigger_type VARCHAR(32) NOT NULL,
    trigger_json TEXT NOT NULL DEFAULT '{}',
    action_type VARCHAR(32) NOT NULL,
    action_json TEXT NOT NULL DEFAULT '{}',
    risk_level VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    last_triggered_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_minikun_automation_recipe_owner
    ON minikun_automation_recipe (owner_id, enabled, updated_at DESC);

CREATE TABLE IF NOT EXISTS minikun_automation_run (
    id UUID PRIMARY KEY,
    recipe_id UUID NOT NULL REFERENCES minikun_automation_recipe(id) ON DELETE CASCADE,
    owner_id VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    trigger_event_json TEXT NOT NULL DEFAULT '{}',
    action_preview_json TEXT NOT NULL DEFAULT '{}',
    confirmation_required BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_minikun_automation_run_owner
    ON minikun_automation_run (owner_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS minikun_guardian_incident (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    summary TEXT NOT NULL,
    probable_cause TEXT NOT NULL DEFAULT '',
    findings_json TEXT NOT NULL DEFAULT '[]',
    timeline_json TEXT NOT NULL DEFAULT '[]',
    opened_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_minikun_guardian_incident_open
    ON minikun_guardian_incident (owner_id, fingerprint) WHERE status = 'OPEN';

CREATE INDEX IF NOT EXISTS idx_minikun_guardian_incident_owner
    ON minikun_guardian_incident (owner_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS minikun_explainability_trace (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    response_id VARCHAR(255) NOT NULL,
    summary TEXT NOT NULL,
    sources_json TEXT NOT NULL DEFAULT '[]',
    tools_json TEXT NOT NULL DEFAULT '[]',
    decisions_json TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_minikun_explainability_response
    ON minikun_explainability_trace (owner_id, response_id);

CREATE INDEX IF NOT EXISTS idx_minikun_explainability_conversation
    ON minikun_explainability_trace (owner_id, conversation_id, created_at DESC);

CREATE TABLE IF NOT EXISTS minikun_personal_timeline (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id VARCHAR(255) NOT NULL,
    title TEXT NOT NULL,
    summary TEXT NOT NULL DEFAULT '',
    details_json TEXT NOT NULL DEFAULT '{}',
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (owner_id, event_type, source_type, source_id)
);

CREATE INDEX IF NOT EXISTS idx_minikun_personal_timeline_owner
    ON minikun_personal_timeline (owner_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS minikun_personal_experiment (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    outcome_id UUID NOT NULL REFERENCES minikun_outcome(id),
    title TEXT NOT NULL,
    hypothesis TEXT NOT NULL,
    protocol TEXT NOT NULL,
    metric_name VARCHAR(255) NOT NULL,
    metric_unit VARCHAR(64) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    baseline_value DOUBLE PRECISION NOT NULL,
    target_value DOUBLE PRECISION NOT NULL,
    duration_days INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ,
    planned_end_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_minikun_personal_experiment_owner
    ON minikun_personal_experiment (owner_id, status, updated_at DESC);

CREATE TABLE IF NOT EXISTS minikun_experiment_check_in (
    id UUID PRIMARY KEY,
    experiment_id UUID NOT NULL REFERENCES minikun_personal_experiment(id) ON DELETE CASCADE,
    owner_id VARCHAR(255) NOT NULL,
    value DOUBLE PRECISION NOT NULL,
    note TEXT NOT NULL DEFAULT '',
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_minikun_experiment_check_in_experiment
    ON minikun_experiment_check_in (experiment_id, owner_id, observed_at);
