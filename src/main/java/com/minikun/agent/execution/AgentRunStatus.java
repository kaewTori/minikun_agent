package com.minikun.agent.execution;

import java.util.Locale;

public enum AgentRunStatus {
    PLANNED,
    RUNNING,
    WAITING_CONFIRMATION,
    COMPLETED,
    UNVERIFIED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    LIMIT_REACHED;

    public boolean terminal() {
        return this == UNVERIFIED || this == COMPLETED || this == COMPLETED_WITH_ERRORS || this == FAILED || this == LIMIT_REACHED;
    }

    public static AgentRunStatus parse(String value) {
        return value == null || value.isBlank() ? null : valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
