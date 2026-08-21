package com.minikun.agent.execution;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AgentRun(
        UUID id,
        String ownerId,
        String conversationId,
        String objective,
        List<String> plannedSteps,
        AgentRunStatus status,
        int currentStep,
        int maxSteps,
        String summary,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public AgentRun {
        Objects.requireNonNull(id, "agent run id must not be null");
        ownerId = require(ownerId, "owner id");
        conversationId = require(conversationId, "conversation id");
        objective = require(objective, "objective");
        plannedSteps = plannedSteps == null ? List.of() : List.copyOf(plannedSteps);
        Objects.requireNonNull(status, "agent run status must not be null");
        if (currentStep < 0 || maxSteps < 1) throw new IllegalArgumentException("invalid agent step bounds");
        summary = Objects.requireNonNullElse(summary, "").trim();
        failureReason = Objects.requireNonNullElse(failureReason, "").trim();
        Objects.requireNonNull(createdAt, "created time must not be null");
        Objects.requireNonNull(updatedAt, "updated time must not be null");
        if (status.terminal() && completedAt == null) {
            throw new IllegalArgumentException("terminal agent run must have completedAt");
        }
        if (!status.terminal() && completedAt != null) {
            throw new IllegalArgumentException("active agent run must not have completedAt");
        }
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank() || "*".equals(value)) {
            throw new IllegalArgumentException(field + " must not be blank or wildcard");
        }
        return value.trim();
    }
}
