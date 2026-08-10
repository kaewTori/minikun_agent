package com.minikun.tools;

import java.util.Objects;

public record ToolParameter(
        String name,
        ToolParameterType type,
        boolean required,
        String description) {
    public ToolParameter {
        Objects.requireNonNull(name, "parameter name must not be null");
        Objects.requireNonNull(type, "parameter type must not be null");
        Objects.requireNonNull(description, "parameter description must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("parameter name must not be blank");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("parameter description must not be blank");
        }
    }
}