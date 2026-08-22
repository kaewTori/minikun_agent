package com.minikun.research;

import java.util.List;
import java.util.Objects;

public record ResearchPlan(String objective, List<ResearchQuestion> questions) {
    public ResearchPlan {
        objective = Objects.requireNonNullElse(objective, "").strip();
        questions = questions == null ? List.of() : questions.stream()
                .filter(Objects::nonNull)
                .limit(8)
                .toList();
        if (objective.isBlank()) {
            throw new IllegalArgumentException("research objective must not be blank");
        }
    }

    public static ResearchPlan fallback(String query) {
        String objective = Objects.requireNonNullElse(query, "").strip();
        return new ResearchPlan(objective, List.of(new ResearchQuestion("q1", objective, "answer the request")));
    }
}
