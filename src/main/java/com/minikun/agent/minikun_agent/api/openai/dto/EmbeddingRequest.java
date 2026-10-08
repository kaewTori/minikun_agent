package com.minikun.agent.minikun_agent.api.openai.dto;

import java.util.List;

public record EmbeddingRequest(String model, Object input) {
    public List<String> texts() {
        if (input instanceof String text) {
            if (text.isBlank()) throw new IllegalArgumentException("embedding input must not be blank");
            return List.of(text);
        }
        if (input instanceof List<?> values) {
            if (values.isEmpty() || values.stream().anyMatch(v -> !(v instanceof String text) || text.isBlank()))
                throw new IllegalArgumentException("embedding input must be a non-empty array of non-blank strings");
            return values.stream().map(String.class::cast).toList();
        }
        throw new IllegalArgumentException("input must be a string or an array of strings");
    }
}
