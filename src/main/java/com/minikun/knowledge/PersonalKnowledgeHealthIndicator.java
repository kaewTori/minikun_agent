package com.minikun.knowledge;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public final class PersonalKnowledgeHealthIndicator implements HealthIndicator {
    private final PersonalKnowledgeService knowledge;

    public PersonalKnowledgeHealthIndicator(PersonalKnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @Override
    public Health health() {
        try {
            KnowledgeStatus status = knowledge.status("default");
            return Health.up().withDetail("sources", status.sources()).withDetail("chunks", status.chunks())
                    .withDetail("semantic", status.semanticAvailable() ? "READY" : "LEXICAL_FALLBACK")
                    .withDetail("localOnly", true).build();
        } catch (RuntimeException exception) {
            return Health.up().withDetail("status", "UNAVAILABLE")
                    .withDetail("reason", "knowledge storage could not be inspected").build();
        }
    }
}
