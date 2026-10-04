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

CREATE TABLE IF NOT EXISTS minikun_task (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    kind VARCHAR(16) NOT NULL DEFAULT 'TASK',
    title TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    parent_id UUID,
    goal_id UUID,
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

ALTER TABLE minikun_task ADD COLUMN IF NOT EXISTS goal_id UUID;

CREATE INDEX IF NOT EXISTS idx_minikun_task_owner_status
    ON minikun_task (owner_id, status, due_at);

CREATE INDEX IF NOT EXISTS idx_minikun_task_follow_up
    ON minikun_task (status, follow_up_at, last_follow_up_at);

CREATE TABLE IF NOT EXISTS minikun_goal (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    progress_percent INTEGER NOT NULL DEFAULT 0,
    metric VARCHAR(255) NOT NULL DEFAULT '',
    current_value DOUBLE PRECISION NOT NULL DEFAULT 0,
    target_value DOUBLE PRECISION NOT NULL DEFAULT 0,
    next_review_at TIMESTAMPTZ,
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Bangkok',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_minikun_goal_owner_status
    ON minikun_goal (owner_id, status, next_review_at);

CREATE TABLE IF NOT EXISTS minikun_goal_review_notification (
    goal_id UUID NOT NULL,
    review_at TIMESTAMPTZ NOT NULL,
    notified_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (goal_id, review_at)
);

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

CREATE TABLE IF NOT EXISTS minikun_guardian_action_audit (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    action_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    detail TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_minikun_guardian_action_audit_owner
    ON minikun_guardian_action_audit (owner_id, created_at DESC);

CREATE TABLE IF NOT EXISTS minikun_guardian_alert_state (
    state_key VARCHAR(128) PRIMARY KEY,
    observed_fingerprint VARCHAR(128) NOT NULL DEFAULT '',
    notified_fingerprint VARCHAR(128) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'UP',
    consecutive_issues INTEGER NOT NULL DEFAULT 0,
    last_notified_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS minikun_computer_audit (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    target TEXT NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    detail TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_minikun_computer_audit_owner
    ON minikun_computer_audit (owner_id, created_at DESC);

CREATE TABLE IF NOT EXISTS minikun_agent_run (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    response_id VARCHAR(255) NOT NULL DEFAULT '',
    objective TEXT NOT NULL,
    planned_steps_json TEXT NOT NULL DEFAULT '[]',
    risk_level VARCHAR(16) NOT NULL DEFAULT 'LOW',
    risk_reasons_json TEXT NOT NULL DEFAULT '[]',
    status VARCHAR(32) NOT NULL,
    current_step INTEGER NOT NULL DEFAULT 0,
    max_steps INTEGER NOT NULL,
    summary TEXT NOT NULL DEFAULT '',
    failure_reason TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

-- Keep existing home-use databases compatible when the agent execution schema evolves.
ALTER TABLE minikun_agent_run ADD COLUMN IF NOT EXISTS risk_level VARCHAR(16) NOT NULL DEFAULT 'LOW';
ALTER TABLE minikun_agent_run ADD COLUMN IF NOT EXISTS risk_reasons_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE minikun_agent_run ADD COLUMN IF NOT EXISTS response_id VARCHAR(255) NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_minikun_agent_run_owner
    ON minikun_agent_run (owner_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_minikun_agent_run_conversation
    ON minikun_agent_run (conversation_id, created_at DESC);

CREATE TABLE IF NOT EXISTS minikun_agent_step (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES minikun_agent_run(id) ON DELETE CASCADE,
    step_index INTEGER NOT NULL,
    tool_call_id VARCHAR(255) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    arguments_json TEXT NOT NULL DEFAULT '{}',
    status VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 1,
    result_json TEXT NOT NULL DEFAULT '',
    error_code VARCHAR(64) NOT NULL DEFAULT '',
    error TEXT NOT NULL DEFAULT '',
    started_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE (run_id, tool_call_id)
);

CREATE INDEX IF NOT EXISTS idx_minikun_agent_step_run
    ON minikun_agent_step (run_id, step_index);
