package com.minikun.agent.minikun_agent.conversation;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Keeps the agent healthy while making temporary loss of persistence visible. */
@Component("conversationPersistence")
public final class ConversationPersistenceHealthIndicator implements HealthIndicator {
    private final ConversationMemoryService memory;

    public ConversationPersistenceHealthIndicator(ConversationMemoryService memory) {
        this.memory = memory;
    }

    @Override
    public Health health() {
        boolean degraded = memory.degraded();
        return (degraded ? Health.status("DEGRADED") : Health.up())
                .withDetail("status", degraded ? "DEGRADED" : "READY")
                .withDetail("mode", degraded ? "in_memory" : "persistent")
                .withDetail("reason", memory.degradedReason())
                .build();
    }
}
