package com.minikun.tools;

import java.util.Objects;

/** A tool result that can either inform the model or be returned as a deterministic final answer. */
public record ToolEvidence(
        String toolName,
        boolean success,
        String content,
        boolean requiresConfirmation,
        boolean finalResponse) {
    public ToolEvidence(String toolName, boolean success, String content, boolean requiresConfirmation) {
        this(toolName, success, content, requiresConfirmation, false);
    }

    public ToolEvidence {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        content = Objects.requireNonNullElse(content, "").trim();
        if (content.isBlank()) {
            throw new IllegalArgumentException("tool evidence content must not be blank");
        }
    }

    public static ToolEvidence verified(String toolName, String content) {
        return new ToolEvidence(toolName, true, content, false);
    }

    public static ToolEvidence failed(String toolName, String content) {
        return new ToolEvidence(toolName, false, content, false);
    }

    public static ToolEvidence pendingConfirmation(String toolName, String content) {
        return new ToolEvidence(toolName, true, content, true);
    }

    public static ToolEvidence finalVerified(String toolName, String content) {
        return new ToolEvidence(toolName, true, content, false, true);
    }

    public static ToolEvidence finalFailed(String toolName, String content) {
        return new ToolEvidence(toolName, false, content, false, true);
    }
}
