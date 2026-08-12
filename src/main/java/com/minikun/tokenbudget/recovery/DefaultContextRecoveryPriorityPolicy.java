package com.minikun.tokenbudget.recovery;

import java.util.List;
import java.util.Objects;

public final class DefaultContextRecoveryPriorityPolicy implements ContextRecoveryPriorityPolicy {
    @Override
    public RecoveryPriorityPlan prioritize(ContextRecoveryPlan plan) {
        Objects.requireNonNull(plan, "context recovery plan must not be null");
        return new RecoveryPriorityPlan(stepsFor(plan.target()));
    }

    private List<RecoveryStep> stepsFor(RecoveryTarget target) {
        return switch (target) {
            case NONE -> List.of();
            case KNOWLEDGE_CONTEXT -> List.of(RecoveryStep.REDUCE_KNOWLEDGE);
            case MEMORY_CONTEXT -> List.of(
                    RecoveryStep.REDUCE_MEMORY,
                    RecoveryStep.REDUCE_KNOWLEDGE);
            case CONVERSATION_HISTORY -> List.of(
                    RecoveryStep.REDUCE_CONVERSATION_HISTORY,
                    RecoveryStep.REDUCE_MEMORY,
                    RecoveryStep.REDUCE_KNOWLEDGE);
            case FULL_FALLBACK -> List.of(
                    RecoveryStep.REQUEST_FALLBACK,
                    RecoveryStep.REDUCE_KNOWLEDGE,
                    RecoveryStep.REDUCE_MEMORY,
                    RecoveryStep.REDUCE_CONVERSATION_HISTORY);
        };
    }
}