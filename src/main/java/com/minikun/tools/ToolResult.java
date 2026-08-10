package com.minikun.tools;

import java.util.Objects;

public record ToolResult(boolean success, Object value, ToolErrorCode errorCode, String error) {
    public ToolResult {
        if (success) {
            if (errorCode != null || error != null) {
                throw new IllegalArgumentException("successful tool result must not contain an error");
            }
        } else {
            Objects.requireNonNull(errorCode, "failed tool result must have an error code");
            Objects.requireNonNull(error, "failed tool result must have an error");
            if (value != null) {
                throw new IllegalArgumentException("failed tool result must not contain a value");
            }
        }
    }

    public static ToolResult success(Object value) {
        return new ToolResult(true, value, null, null);
    }

    public static ToolResult failure(ToolErrorCode errorCode, String error) {
        return new ToolResult(false, null, errorCode, error);
    }
}