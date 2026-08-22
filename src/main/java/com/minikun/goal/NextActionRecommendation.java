package com.minikun.goal;

import java.util.UUID;

public record NextActionRecommendation(
        UUID goalId,
        String goalTitle,
        UUID taskId,
        String action,
        String reason,
        int priority) {
    public NextActionRecommendation {
        if (goalId == null) throw new IllegalArgumentException("goal id must not be null");
        goalTitle = required(goalTitle, "goal title");
        action = required(action, "next action");
        reason = required(reason, "reason");
        if (priority < 1 || priority > 5) throw new IllegalArgumentException("priority must be between 1 and 5");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
