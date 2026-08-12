package com.minikun.tokenbudget.recovery;

import com.minikun.tokenbudget.pressure.ContextPressureDecision;
import com.minikun.tokenbudget.pressure.ContextPressureLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextRecoveryPlannerTest {
    private final ContextRecoveryPlanner planner = new DefaultContextRecoveryPlanner();

    @Test
    void normalPressureNeedsNoRecovery() {
        ContextRecoveryPlan plan = planner.plan(decision(ContextPressureLevel.NORMAL));

        assertEquals(RecoveryPriority.LOW, plan.priority());
        assertEquals(RecoveryTarget.NONE, plan.target());
        assertFalse(plan.required());
    }

    @Test
    void warningPressureReducesKnowledgeContext() {
        ContextRecoveryPlan plan = planner.plan(decision(ContextPressureLevel.WARNING));

        assertEquals(RecoveryPriority.MEDIUM, plan.priority());
        assertEquals(RecoveryTarget.KNOWLEDGE_CONTEXT, plan.target());
        assertTrue(plan.required());
    }

    @Test
    void criticalPressureUsesFallback() {
        ContextRecoveryPlan plan = planner.plan(decision(ContextPressureLevel.CRITICAL));

        assertEquals(RecoveryPriority.HIGH, plan.priority());
        assertEquals(RecoveryTarget.FULL_FALLBACK, plan.target());
        assertTrue(plan.required());
    }

    @Test
    void rejectsNullDecision() {
        assertThrows(NullPointerException.class, () -> planner.plan(null));
    }

    private ContextPressureDecision decision(ContextPressureLevel level) {
        return new ContextPressureDecision(100, 500, level, level != ContextPressureLevel.NORMAL);
    }
}