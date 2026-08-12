package com.minikun.tokenbudget.recovery;

import java.util.Map;
import java.util.Objects;

public final class DefaultRecoveryStrategyRegistry implements RecoveryStrategyRegistry {
    private final Map<RecoveryStep, RecoveryStrategyHandler> handlers;

    public DefaultRecoveryStrategyRegistry(Map<RecoveryStep, RecoveryStrategyHandler> handlers) {
        Objects.requireNonNull(handlers, "recovery strategy handlers must not be null");
        if (handlers.entrySet().stream().anyMatch(entry -> entry.getKey() == null)) {
            throw new NullPointerException("recovery strategy step must not be null");
        }
        if (handlers.entrySet().stream().anyMatch(entry -> entry.getValue() == null)) {
            throw new NullPointerException("recovery strategy handler must not be null");
        }
        this.handlers = Map.copyOf(handlers);
    }

    @Override
    public RecoveryStrategyHandler resolve(RecoveryStep step) {
        Objects.requireNonNull(step, "recovery step must not be null");
        RecoveryStrategyHandler handler = handlers.get(step);
        if (handler == null) {
            throw new IllegalArgumentException("no recovery strategy registered for step: " + step);
        }
        return handler;
    }
}