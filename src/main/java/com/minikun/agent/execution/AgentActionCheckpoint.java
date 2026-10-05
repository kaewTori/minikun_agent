package com.minikun.agent.execution;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AgentActionCheckpoint(UUID runId, AgentActionPlan plan, int nextStep, String phase,
        Map<String, Object> prepared, String digest, Instant consentExpiresAt, Instant deadline,
        Map<String, Object> evidence, int revision) {
    public AgentActionCheckpoint {
        prepared = Map.copyOf(prepared == null ? Map.of() : prepared);
        evidence = Map.copyOf(evidence == null ? Map.of() : evidence);
    }
    public AgentActionCheckpoint change(String phase, int nextStep, Map<String, Object> prepared,
            String digest, Instant consentExpiresAt, Map<String, Object> evidence) {
        return new AgentActionCheckpoint(runId, plan, nextStep, phase, prepared, digest, consentExpiresAt,
                deadline, evidence, revision);
    }
    public AgentActionCheckpoint revised(int revision) {
        return new AgentActionCheckpoint(runId, plan, nextStep, phase, prepared, digest, consentExpiresAt,
                deadline, evidence, revision);
    }
}
