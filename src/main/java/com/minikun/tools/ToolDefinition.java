package com.minikun.tools;

import java.util.Map;
import java.util.Objects;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.TreeMap;

public record ToolDefinition(
        String name,
        String description,
        Map<String, ToolParameter> parameters) {
    public ToolDefinition {
        Objects.requireNonNull(name, "tool name must not be null");
        Objects.requireNonNull(description, "tool description must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("tool description must not be blank");
        }
        parameters = parameters == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(parameters)));
        for (Map.Entry<String, ToolParameter> entry : parameters.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "parameter name must not be null");
            Objects.requireNonNull(entry.getValue(), "tool parameter must not be null");
            if (!entry.getKey().equals(entry.getValue().name())) {
                throw new IllegalArgumentException("parameter map key must match parameter name");
            }
        }
    }
}