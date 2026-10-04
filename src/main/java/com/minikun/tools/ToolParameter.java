package com.minikun.tools;

import java.util.Objects;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolParameter(
        String name,
        ToolParameterType type,
        boolean required,
        String description,
        Map<String, Object> schema) {
    public ToolParameter(String name, ToolParameterType type, boolean required, String description) {
        this(name, type, required, description, Map.of());
    }

    public ToolParameter {
        Objects.requireNonNull(name, "parameter name must not be null");
        Objects.requireNonNull(type, "parameter type must not be null");
        Objects.requireNonNull(description, "parameter description must not be null");
        schema = schema == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(schema));
        if (name.isBlank()) {
            throw new IllegalArgumentException("parameter name must not be blank");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("parameter description must not be blank");
        }
    }
}
