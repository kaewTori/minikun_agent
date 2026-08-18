CREATE TABLE IF NOT EXISTS minikun_planner_event (
    id UUID PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL,
    title TEXT NOT NULL,
    note TEXT NOT NULL DEFAULT '',
    starts_at TIMESTAMPTZ NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    remind_before_minutes INTEGER NOT NULL DEFAULT 0,
    recurrence VARCHAR(16) NOT NULL DEFAULT 'NONE',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    next_notify_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_minikun_planner_due
    ON minikun_planner_event (status, next_notify_at);

CREATE INDEX IF NOT EXISTS idx_minikun_planner_conversation
    ON minikun_planner_event (conversation_id, starts_at);

CREATE TABLE IF NOT EXISTS minikun_planner_pending_confirmation (
    conversation_id VARCHAR(255) PRIMARY KEY,
    action VARCHAR(32) NOT NULL,
    arguments_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS minikun_weather_alert_state (
    alert_key VARCHAR(128) PRIMARY KEY,
    last_sent_date DATE,
    last_sent_at TIMESTAMPTZ
);
