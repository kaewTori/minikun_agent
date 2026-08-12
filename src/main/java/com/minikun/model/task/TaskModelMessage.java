package com.minikun.model.task;

import java.util.Objects;

public record TaskModelMessage(String role, String content) {
    public TaskModelMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
    }
}