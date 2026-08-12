package com.minikun.tokenbudget.pressure;

import com.minikun.model.ChatModelId;
import com.minikun.model.capability.ModelCapability;
import com.minikun.model.capability.ModelRole;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextPressureAnalyzerTest {
    private static final ModelCapability CAPABILITY =
            new ModelCapability(ChatModelId.TINYGRAD, ModelRole.CHAT, 16_384, 4_096);
    private final ContextPressureAnalyzer analyzer = new DefaultContextPressureAnalyzer();

    @Test
    void classifiesNormalOutput() {
        ContextPressureDecision decision = analyze(1_024);

        assertEquals(ContextPressureLevel.NORMAL, decision.level());
        assertFalse(decision.requiresRecovery());
    }

    @Test
    void classifiesWarningRange() {
        ContextPressureDecision decision = analyze(256);

        assertEquals(ContextPressureLevel.WARNING, decision.level());
        assertTrue(decision.requiresRecovery());
    }

    @Test
    void classifiesCriticalRange() {
        ContextPressureDecision decision = analyze(255);

        assertEquals(ContextPressureLevel.CRITICAL, decision.level());
        assertTrue(decision.requiresRecovery());
    }

    @Test
    void classifiesZeroOutputAsCritical() {
        ContextPressureDecision decision = analyze(0);

        assertEquals(ContextPressureLevel.CRITICAL, decision.level());
        assertEquals(0, decision.availableOutputTokens());
    }

    @Test
    void rejectsNullCapability() {
        assertThrows(NullPointerException.class,
                () -> analyzer.analyze(null, new TokenBudgetAllocation(100, 1_024, false)));
    }

    @Test
    void rejectsNullAllocation() {
        assertThrows(NullPointerException.class, () -> analyzer.analyze(CAPABILITY, null));
    }

    private ContextPressureDecision analyze(long availableOutputTokens) {
        return analyzer.analyze(CAPABILITY,
                new TokenBudgetAllocation(500, availableOutputTokens, false));
    }
}