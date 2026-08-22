package com.minikun.research;

import java.util.Objects;

public record ResearchQuestion(String id, String query, String purpose) {
    public ResearchQuestion {
        id = requireText(id, "question id");
        query = requireText(query, "question query");
        purpose = Objects.requireNonNullElse(purpose, "").strip();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
