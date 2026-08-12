package com.minikun.tokenbudget.recovery;

import java.util.List;
import java.util.Objects;

public record RecoveryPriorityPlan(List<RecoveryStep> steps) {
    public RecoveryPriorityPlan {
        Objects.requireNonNull(steps, "recovery steps must not be null");
        if (steps.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("recovery steps must not contain null");
        }
        steps = List.copyOf(steps);
    }
}