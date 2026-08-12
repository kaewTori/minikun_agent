package com.minikun.tokenbudget.recovery;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecoveryStrategyRegistryTest {
    private static final RecoveryStrategyHandler KNOWLEDGE_HANDLER = step -> null;
    private static final RecoveryStrategyHandler MEMORY_HANDLER = step -> null;

    @Test
    void resolvesRegisteredHandler() {
        RecoveryStrategyRegistry registry = new DefaultRecoveryStrategyRegistry(
                Map.of(RecoveryStep.REDUCE_KNOWLEDGE, KNOWLEDGE_HANDLER));

        assertSame(KNOWLEDGE_HANDLER, registry.resolve(RecoveryStep.REDUCE_KNOWLEDGE));
    }

    @Test
    void snapshotsHandlerMap() {
        Map<RecoveryStep, RecoveryStrategyHandler> handlers = new HashMap<>();
        handlers.put(RecoveryStep.REDUCE_KNOWLEDGE, KNOWLEDGE_HANDLER);
        RecoveryStrategyRegistry registry = new DefaultRecoveryStrategyRegistry(handlers);
        handlers.put(RecoveryStep.REDUCE_KNOWLEDGE, MEMORY_HANDLER);

        assertSame(KNOWLEDGE_HANDLER, registry.resolve(RecoveryStep.REDUCE_KNOWLEDGE));
    }

    @Test
    void rejectsInvalidConstructorArguments() {
        assertThrows(NullPointerException.class, () -> new DefaultRecoveryStrategyRegistry(null));
        Map<RecoveryStep, RecoveryStrategyHandler> nullStepHandlers = new HashMap<>();
        nullStepHandlers.put(null, KNOWLEDGE_HANDLER);
        assertThrows(NullPointerException.class,
            () -> new DefaultRecoveryStrategyRegistry(nullStepHandlers));
        Map<RecoveryStep, RecoveryStrategyHandler> nullHandlerHandlers = new HashMap<>();
        nullHandlerHandlers.put(RecoveryStep.REDUCE_KNOWLEDGE, null);
        assertThrows(NullPointerException.class,
            () -> new DefaultRecoveryStrategyRegistry(nullHandlerHandlers));
    }

    @Test
    void rejectsInvalidResolutionArguments() {
        RecoveryStrategyRegistry registry = new DefaultRecoveryStrategyRegistry(
                Map.of(RecoveryStep.REDUCE_KNOWLEDGE, KNOWLEDGE_HANDLER));

        assertThrows(NullPointerException.class, () -> registry.resolve(null));
        assertThrows(IllegalArgumentException.class,
                () -> registry.resolve(RecoveryStep.REDUCE_MEMORY));
    }
}