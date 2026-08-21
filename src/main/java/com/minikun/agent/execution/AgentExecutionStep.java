package com.minikun.agent.execution;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AgentExecutionStep(
        UUID id,
        UUID runId,
        int stepIndex,
        String toolCallId,
        String toolName,
        String argumentsJson,
        AgentStepStatus status,
        int attempts,
        String resultJson,
        String errorCode,
        String error,
        Instant startedAt,
        Instant updatedAt,
        Instant completedAt) {

    public AgentExecutionStep {
        Objects.requireNonNull(id, "agent step id must not be null");
        Objects.requireNonNull(runId, "agent run id must not be null");
        if (stepIndex < 1 || attempts < 1) throw new IllegalArgumentException("invalid agent step counters");
        toolCallId = require(toolCallId, "tool call id");
        toolName = require(toolName, "tool name");
        argumentsJson = Objects.requireNonNullElse(argumentsJson, "{}");
        Objects.requireNonNull(status, "agent step status must not be null");
        resultJson = Objects.requireNonNullElse(resultJson, "");
        errorCode = Objects.requireNonNullElse(errorCode, "");
        error = Objects.requireNonNullElse(error, "");
        Objects.requireNonNull(startedAt, "step start time must not be null");
        Objects.requireNonNull(updatedAt, "step update time must not be null");
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
