package com.minikun.model.task;

import java.util.List;
import java.util.Objects;

public record TaskModelRequest(
        List<TaskModelMessage> messages,
        int maxOutputTokens,
        double temperature,
        ResponseFormat responseFormat, com.minikun.model.GenerationOptions.Reasoning reasoning) {

    public TaskModelRequest(List<TaskModelMessage> messages, int maxOutputTokens, double temperature, ResponseFormat responseFormat) {
        this(messages, maxOutputTokens, temperature, responseFormat, com.minikun.model.GenerationOptions.Reasoning.OFF);
    }
    public TaskModelRequest {
        reasoning = reasoning == null ? com.minikun.model.GenerationOptions.Reasoning.OFF : reasoning;
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(responseFormat, "responseFormat must not be null");
        messages = List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        if (!Double.isFinite(temperature) || temperature < 0.0) {
            throw new IllegalArgumentException("temperature must be finite and non-negative");
        }
    }

    public enum ResponseFormat {
        TEXT,
        JSON_OBJECT
    }
}