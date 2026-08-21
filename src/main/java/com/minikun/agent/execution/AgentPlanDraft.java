package com.minikun.agent.execution;

import java.util.List;

public record AgentPlanDraft(String objective, List<String> steps) {
    public AgentPlanDraft {
        if (objective == null || objective.isBlank()) {
            throw new IllegalArgumentException("agent objective must not be blank");
        }
        objective = objective.trim();
        steps = steps == null ? List.of() : steps.stream()
                .filter(step -> step != null && !step.isBlank())
                .map(String::trim)
                .toList();
        if (steps.isEmpty()) throw new IllegalArgumentException("agent plan must contain at least one step");
    }
}
