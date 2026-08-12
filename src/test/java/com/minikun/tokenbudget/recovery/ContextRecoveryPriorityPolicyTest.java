package com.minikun.tokenbudget.recovery;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextRecoveryPriorityPolicyTest {
    private final ContextRecoveryPriorityPolicy policy = new DefaultContextRecoveryPriorityPolicy();

    @Test
    void returnsNoStepsForNone() {
        assertEquals(List.of(), prioritize(RecoveryTarget.NONE).steps());
    }

    @Test
    void reducesKnowledgeFirstForKnowledgeTarget() {
        assertEquals(List.of(RecoveryStep.REDUCE_KNOWLEDGE),
                prioritize(RecoveryTarget.KNOWLEDGE_CONTEXT).steps());
    }

    @Test
    void reducesMemoryThenKnowledgeForMemoryTarget() {
        assertEquals(List.of(RecoveryStep.REDUCE_MEMORY, RecoveryStep.REDUCE_KNOWLEDGE),
                prioritize(RecoveryTarget.MEMORY_CONTEXT).steps());
    }

    @Test
    void reducesConversationThenMemoryThenKnowledgeForHistoryTarget() {
        assertEquals(List.of(
                        RecoveryStep.REDUCE_CONVERSATION_HISTORY,
                        RecoveryStep.REDUCE_MEMORY,
                        RecoveryStep.REDUCE_KNOWLEDGE),
                prioritize(RecoveryTarget.CONVERSATION_HISTORY).steps());
    }

    @Test
    void requestsFallbackFirstForFullFallback() {
        assertEquals(List.of(
                        RecoveryStep.REQUEST_FALLBACK,
                        RecoveryStep.REDUCE_KNOWLEDGE,
                        RecoveryStep.REDUCE_MEMORY,
                        RecoveryStep.REDUCE_CONVERSATION_HISTORY),
                prioritize(RecoveryTarget.FULL_FALLBACK).steps());
    }

    @Test
    void rejectsNullPlan() {
        assertThrows(NullPointerException.class, () -> policy.prioritize(null));
    }

    @Test
    void copiesStepsImmutably() {
        List<RecoveryStep> steps = new ArrayList<>(List.of(RecoveryStep.REDUCE_KNOWLEDGE));
        RecoveryPriorityPlan plan = new RecoveryPriorityPlan(steps);
        steps.add(RecoveryStep.REDUCE_MEMORY);

        assertEquals(List.of(RecoveryStep.REDUCE_KNOWLEDGE), plan.steps());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.steps().add(RecoveryStep.REDUCE_MEMORY));
    }

    private RecoveryPriorityPlan prioritize(RecoveryTarget target) {
        return policy.prioritize(new ContextRecoveryPlan(
                RecoveryPriority.MEDIUM, target, target != RecoveryTarget.NONE));
    }
}