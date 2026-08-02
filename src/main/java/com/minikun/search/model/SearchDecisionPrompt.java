package com.minikun.search.model;

import java.util.Objects;

public record SearchDecisionPrompt(String instructions, String currentDate, String userMessage) {
    public SearchDecisionPrompt {
        Objects.requireNonNull(instructions, "instructions must not be null");
        Objects.requireNonNull(currentDate, "currentDate must not be null");
        Objects.requireNonNull(userMessage, "userMessage must not be null");
        if (instructions.isBlank()) {
            throw new IllegalArgumentException("instructions must not be blank");
        }
        if (currentDate.isBlank()) {
            throw new IllegalArgumentException("currentDate must not be blank");
        }
        if (userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
    }
}