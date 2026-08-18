package com.minikun.planner;

import java.time.Instant;
import java.util.Optional;

/** Persistence boundary for planner proposals awaiting confirmation. */
public interface PlannerConfirmationStore {
    void save(PendingPlannerConfirmation confirmation);

    Optional<PendingPlannerConfirmation> find(String conversationId, Instant now);

    void clear(String conversationId);
}
