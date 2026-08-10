package com.minikun.tools;

import java.util.Map;
import java.util.Objects;

public record ToolCall(String id, String name, Map<String, Object> arguments) {
    public ToolCall {
        Objects.requireNonNull(id, "tool call id must not be null");
        Objects.requireNonNull(name, "tool name must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("tool call id must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}