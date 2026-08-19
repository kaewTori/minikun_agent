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
    owner_id VARCHAR(255) NOT NULL DEFAULT 'default',
    action VARCHAR(32) NOT NULL,
    arguments_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE minikun_planner_pending_confirmation
    ADD COLUMN IF NOT EXISTS owner_id VARCHAR(255) NOT NULL DEFAULT 'default';

CREATE TABLE IF NOT EXISTS minikun_weather_alert_state (
    alert_key VARCHAR(128) PRIMARY KEY,
    last_sent_date DATE,
    last_sent_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS minikun_task (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    kind VARCHAR(16) NOT NULL DEFAULT 'TASK',
    title TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    parent_id UUID,
    due_at TIMESTAMPTZ,
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Bangkok',
    next_action TEXT NOT NULL DEFAULT '',
    waiting_for TEXT NOT NULL DEFAULT '',
    follow_up_at TIMESTAMPTZ,
    last_follow_up_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_minikun_task_owner_status
    ON minikun_task (owner_id, status, due_at);

CREATE INDEX IF NOT EXISTS idx_minikun_task_follow_up
    ON minikun_task (status, follow_up_at, last_follow_up_at);

CREATE TABLE IF NOT EXISTS minikun_proactive_briefing_state (
    briefing_key VARCHAR(128) PRIMARY KEY,
    last_sent_date DATE,
    last_sent_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS minikun_notification_delivery (
    id UUID PRIMARY KEY,
    source_type VARCHAR(32) NOT NULL,
    source_id VARCHAR(255) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    title TEXT NOT NULL,
    message TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    failure_reason TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_minikun_notification_delivery_source
    ON minikun_notification_delivery (source_type, source_id, attempted_at DESC);

CREATE INDEX IF NOT EXISTS idx_minikun_notification_delivery_status
    ON minikun_notification_delivery (status, attempted_at DESC);

CREATE TABLE IF NOT EXISTS minikun_external_calendar_notification (
    event_uid TEXT NOT NULL,
    occurrence_start TIMESTAMPTZ NOT NULL,
    notified_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_uid, occurrence_start)
);

CREATE TABLE IF NOT EXISTS minikun_reminder_action (
    id UUID PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL,
    event_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL,
    snoozed_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_minikun_reminder_action_event
    ON minikun_reminder_action (event_id, created_at DESC);
