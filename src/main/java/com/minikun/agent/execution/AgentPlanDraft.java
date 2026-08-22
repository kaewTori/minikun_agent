package com.minikun.agent.execution;

import java.util.List;

public record AgentPlanDraft(
        String objective,
        List<String> steps,
        AgentRiskAssessment riskAssessment) {
    public AgentPlanDraft(String objective, List<String> steps) {
        this(objective, steps, new AgentRiskAssessor().assess(objective, steps));
    }

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
        if (riskAssessment == null) throw new IllegalArgumentException("risk assessment must not be null");
    }
}
