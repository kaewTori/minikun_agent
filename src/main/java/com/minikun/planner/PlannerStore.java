package com.minikun.planner;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlannerStore {
    PlannerEvent create(PlannerEvent event);

    Optional<PlannerEvent> find(UUID id, String conversationId);

    List<PlannerEvent> list(String conversationId);

    List<PlannerEvent> findDue(Instant now);

    default List<PlannerEvent> listUpcoming(Instant from, Instant to) {
        return List.of();
    }

    PlannerEvent update(PlannerEvent event);

    boolean cancel(UUID id, String conversationId, Instant updatedAt);

    void markDelivered(PlannerEvent event, Instant now);

    default void recordAction(
            UUID actionId,
            String conversationId,
            UUID eventId,
            String action,
            Instant snoozedUntil,
            Instant createdAt) {
        // Optional for non-durable test stores.
    }
}
