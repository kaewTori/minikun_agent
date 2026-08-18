package com.minikun.tools;

import java.util.Objects;

/** A tool result that is ready to be carried into the MCS/PCS answer turn. */
public record ToolEvidence(String toolName, boolean success, String content, boolean requiresConfirmation) {
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
}
