package com.minikun.agent.execution;

/** Risk boundary for an autonomous personal-agent plan. */
public enum AgentRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public boolean requiresExplicitReview() {
        return this == HIGH || this == CRITICAL;
    }
}
