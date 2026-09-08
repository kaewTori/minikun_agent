package com.minikun.tokenbudget.runtime;

import com.minikun.model.ChatModelId;
import com.minikun.model.GenerationOptions;
import com.minikun.model.capability.ModelCapability;
import com.minikun.model.capability.ModelRole;
import com.minikun.tokenbudget.domain.TokenBudget;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;
import com.minikun.tokenbudget.integration.GenerationOptionsResolver;
import com.minikun.tokenbudget.planner.DynamicTokenPlanner;
import com.minikun.tokenbudget.pressure.ContextPressureLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicGenerationOptionsFactoryTest {
    private static final ModelCapability CAPABILITY =
            new ModelCapability(ChatModelId.EXISTING, ModelRole.CHAT, 16_384, 4_096);
    private static final TokenBudget BUDGET = new TokenBudget(16_384, 0, 2_048);

    @Test
    void delegatesPlanningAndResolution() {
        AtomicReference<String> receivedPrompt = new AtomicReference<>();
        GenerationOptions existing = new GenerationOptions(0.7, null, List.of("END"));
        GenerationOptions resolved = new GenerationOptions(0.7, 1_234, List.of("END"));
        DynamicTokenPlanner planner = (capability, budget, prompt) -> {
            assertSame(CAPABILITY, capability);
            assertSame(BUDGET, budget);
            receivedPrompt.set(prompt);
            return new TokenBudgetAllocation(200, 1_234, false);
        };
        GenerationOptionsResolver resolver = (options, allocation) -> {
            assertSame(existing, options);
            assertEquals(1_234, allocation.availableOutputTokens());
            return resolved;
        };

        GenerationOptions actual = new DynamicGenerationOptionsFactory(planner, resolver)
                .create(existing, CAPABILITY, BUDGET, "system\nuser");

        assertEquals("system\nuser", receivedPrompt.get());
        assertSame(resolved, actual);
    }

    @Test
    void capsOutputAtRequestedMaximum() {
        DynamicGenerationOptionsFactory factory = factory(100, 1_000, false);

        GenerationOptions resolved = factory.create(
                new GenerationOptions(0.7, 100, List.of("END")), CAPABILITY, BUDGET, "prompt");

        assertEquals(0.2, resolved.temperature());
        assertEquals(100, resolved.maxTokens());
        assertEquals(List.of("STOP"), resolved.stop());
    }

    @Test
    void exposesBudgetDecisionAndPressureLevel() {
        DynamicGenerationOptionsResult normal = factory(200, 1_024, true).createWithDiagnostics(
                new GenerationOptions(0.7, 3_000, List.of()), CAPABILITY, BUDGET, "prompt");
        DynamicGenerationOptionsResult warning = factory(200, 500, false).createWithDiagnostics(
                new GenerationOptions(0.7, null, List.of()), CAPABILITY, BUDGET, "prompt");
        DynamicGenerationOptionsResult critical = factory(200, 255, false).createWithDiagnostics(
                new GenerationOptions(0.7, 100, List.of()), CAPABILITY, BUDGET, "prompt");

        assertEquals(200, normal.tokenBudgetDecision().inputTokens());
        assertEquals(1_024, normal.tokenBudgetDecision().allocatedOutputTokens());
        assertTrue(normal.tokenBudgetDecision().truncated());
        assertFalse(normal.tokenBudgetDecision().cappedByApplicationLimit());
        assertFalse(normal.tokenBudgetDecision().cappedByRequestLimit());
        assertEquals(ContextPressureLevel.NORMAL, normal.pressureLevel());
        assertEquals(ContextPressureLevel.WARNING, warning.pressureLevel());
        assertEquals(ContextPressureLevel.CRITICAL, critical.pressureLevel());
        assertTrue(critical.tokenBudgetDecision().cappedByRequestLimit());
        assertEquals(100, critical.generationOptions().maxTokens());
    }

    @Test
    void rejectsNullDependenciesAndInputs() {
        assertThrows(NullPointerException.class,
                () -> new DynamicGenerationOptionsFactory(null, (options, allocation) -> options));
        assertThrows(NullPointerException.class,
                () -> new DynamicGenerationOptionsFactory((capability, budget, prompt) -> null, null));
        DynamicGenerationOptionsFactory factory = factory(1, 1, false);
        assertThrows(NullPointerException.class, () -> factory.create(null, null, BUDGET, "prompt"));
        assertThrows(NullPointerException.class, () -> factory.create(null, CAPABILITY, null, "prompt"));
        assertThrows(NullPointerException.class, () -> factory.create(null, CAPABILITY, BUDGET, null));
    }

    private DynamicGenerationOptionsFactory factory(long input, long output, boolean truncated) {
        return new DynamicGenerationOptionsFactory(
                (capability, budget, prompt) -> new TokenBudgetAllocation(input, output, truncated),
                (options, allocation) -> new GenerationOptions(0.2,
                        Math.toIntExact(allocation.availableOutputTokens()), List.of("STOP")));
    }
}
