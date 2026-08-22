package com.minikun.agent.execution;

import java.util.List;

public record AgentRiskAssessment(AgentRiskLevel level, List<String> reasons) {
    public AgentRiskAssessment {
        if (level == null) throw new IllegalArgumentException("risk level must not be null");
        reasons = reasons == null ? List.of() : reasons.stream()
                .filter(reason -> reason != null && !reason.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public String summary() {
        return reasons.isEmpty() ? level.name() : level.name() + ": " + String.join(", ", reasons);
    }
}
