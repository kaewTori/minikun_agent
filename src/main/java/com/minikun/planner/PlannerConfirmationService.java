package com.minikun.planner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.minikun.agent.minikun_agent.conversation.ConversationId;

/** Owns the durable pending-confirmation state for planner write operations. */
@Component
@ConditionalOnProperty(name = "minikun.planner.enabled", havingValue = "true", matchIfMissing = true)
public final class PlannerConfirmationService {
    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(30);

    private final PlannerConfirmationStore store;
    private final Clock clock;

    public PlannerConfirmationService(PlannerConfirmationStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "planner confirmation store must not be null");
        this.clock = Objects.requireNonNull(clock, "planner confirmation clock must not be null");
    }

    public PendingPlannerConfirmation save(
            ConversationId conversationId, String action, Map<String, Object> arguments) {
        return save(conversationId, "default", action, arguments);
    }

    public PendingPlannerConfirmation save(
            ConversationId conversationId, String ownerId, String action, Map<String, Object> arguments) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        arguments.forEach((key, value) -> {
            if (key != null && !"confirmed".equals(key) && value != null) {
                normalized.put(key, value);
            }
        });
        Instant now = clock.instant();
        PendingPlannerConfirmation confirmation = new PendingPlannerConfirmation(
                conversationId.value(), owner(ownerId), action, normalized, now, now.plus(CONFIRMATION_TTL));
        store.save(confirmation);
        return confirmation;
    }

    public Optional<PendingPlannerConfirmation> find(ConversationId conversationId) {
        return store.find(conversationId.value(), clock.instant());
    }

    public Optional<PendingPlannerConfirmation> find(ConversationId conversationId, String ownerId) {
        return store.find(conversationId.value(), owner(ownerId), clock.instant());
    }

    public Map<String, Object> confirmedArguments(PendingPlannerConfirmation confirmation) {
        Map<String, Object> arguments = new LinkedHashMap<>(confirmation.arguments());
        arguments.put("confirmed", true);
        return Collections.unmodifiableMap(arguments);
    }

    public void clear(ConversationId conversationId) {
        store.clear(conversationId.value());
    }

    private String owner(String value) {
        if (value == null || value.isBlank() || "*".equals(value)) {
            throw new IllegalArgumentException("owner id must not be blank or wildcard");
        }
        return value.trim();
    }
}
