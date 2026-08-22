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
    started_at TIMESTAMP WITH TIME ZONE,
    planned_end_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_minikun_personal_experiment_owner
    ON minikun_personal_experiment (owner_id, status, updated_at DESC);

CREATE TABLE IF NOT EXISTS minikun_experiment_check_in (
    id UUID PRIMARY KEY,
    experiment_id UUID NOT NULL REFERENCES minikun_personal_experiment(id) ON DELETE CASCADE,
    owner_id VARCHAR(255) NOT NULL,
    value DOUBLE PRECISION NOT NULL,
    note TEXT NOT NULL DEFAULT '',
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_minikun_experiment_check_in_experiment
    ON minikun_experiment_check_in (experiment_id, owner_id, observed_at);
