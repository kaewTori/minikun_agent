package com.minikun.planner;

import java.time.Instant;
import java.util.Optional;

/** Persistence boundary for planner proposals awaiting confirmation. */
public interface PlannerConfirmationStore {
    void save(PendingPlannerConfirmation confirmation);

    Optional<PendingPlannerConfirmation> find(String conversationId, Instant now);

    default Optional<PendingPlannerConfirmation> find(String conversationId, String ownerId, Instant now) {
        return find(conversationId, now).filter(value -> value.ownerId().equals(ownerId));
    }

    void clear(String conversationId);
}
