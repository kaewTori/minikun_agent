package com.minikun.agent.minikun_agent.api.openai.dto;

import java.util.List;

public record EmbeddingRequest(String model, Object input) {
    public List<String> texts() {
        if (input instanceof String text) {
            return List.of(text);
        }
        if (input instanceof List<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        throw new IllegalArgumentException("input must be a string or an array of strings");
    }
}