package com.minikun.tokenbudget.runtime;

import com.minikun.model.ChatModelId;
import com.minikun.model.GenerationOptions;
import com.minikun.model.capability.ModelCapability;
import com.minikun.model.capability.ModelRole;
import com.minikun.tokenbudget.domain.TokenBudget;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;
import com.minikun.tokenbudget.integration.GenerationOptionsResolver;
import com.minikun.tokenbudget.planner.DynamicTokenPlanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamicGenerationOptionsFactoryTest {
    private static final ModelCapability CAPABILITY =
            new ModelCapability(ChatModelId.TINYGRAD, ModelRole.CHAT, 16_384, 4_096);
    private static final TokenBudget BUDGET = new TokenBudget(16_384, 0, 2_048);

    @Test
    void delegatesPlanningAndResolutionWithoutRecomputingBudget() {
        AtomicReference<ModelCapability> receivedCapability = new AtomicReference<>();
        AtomicReference<TokenBudget> receivedBudget = new AtomicReference<>();
        AtomicReference<String> receivedPrompt = new AtomicReference<>();
        GenerationOptions existing = new GenerationOptions(0.7, null, List.of("END"));
        GenerationOptions resolved = new GenerationOptions(0.7, 1_234, List.of("END"));
        DynamicTokenPlanner planner = (capability, budget, prompt) -> {
            receivedCapability.set(capability);
            receivedBudget.set(budget);
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

        assertSame(CAPABILITY, receivedCapability.get());
        assertSame(BUDGET, receivedBudget.get());
        assertEquals("system\nuser", receivedPrompt.get());
        assertSame(resolved, actual);
    }

    @Test
    void preservesTemperatureAndStopWhenRequestCapClampsOutput() {
        GenerationOptions existing = new GenerationOptions(0.7, 100, List.of("END"));
        DynamicGenerationOptionsFactory factory = new DynamicGenerationOptionsFactory(
                (capability, budget, prompt) -> new TokenBudgetAllocation(100, 1_000, false),
                (options, allocation) -> new GenerationOptions(0.2, 1_000, List.of("STOP")));

        GenerationOptions resolved = factory.create(existing, CAPABILITY, BUDGET, "prompt");

        assertEquals(0.2, resolved.temperature());
        assertEquals(100, resolved.maxTokens());
        assertEquals(List.of("STOP"), resolved.stop());
    }

    @Test
    void treatsNullRequestMaximumAsUncapped() {
        GenerationOptions existing = new GenerationOptions(0.7, null, List.of("END"));
        DynamicGenerationOptionsFactory factory = new DynamicGenerationOptionsFactory(
                (capability, budget, prompt) -> new TokenBudgetAllocation(100, 1_000, false),
                (options, allocation) -> new GenerationOptions(0.2, 1_000, List.of("STOP")));

        GenerationOptions resolved = factory.create(existing, CAPABILITY, BUDGET, "prompt");

        assertEquals(1_000, resolved.maxTokens());
    }

    @Test
    void treatsZeroRequestMaximumAsAnIntentionalCap() {
        GenerationOptions existing = new GenerationOptions(0.7, 0, List.of("END"));
        DynamicGenerationOptionsFactory factory = new DynamicGenerationOptionsFactory(
                (capability, budget, prompt) -> new TokenBudgetAllocation(100, 1_000, false),
                (options, allocation) -> new GenerationOptions(0.2, 1_000, List.of("STOP")));

        GenerationOptions resolved = factory.create(existing, CAPABILITY, BUDGET, "prompt");

        assertEquals(0, resolved.maxTokens());
    }

    @Test
    void exposesDiagnosticsWithoutChangingResolvedOptions() {
        GenerationOptions existing = new GenerationOptions(0.7, 3_000, List.of("END"));
        DynamicGenerationOptionsFactory factory = new DynamicGenerationOptionsFactory(
                (capability, budget, prompt) -> new TokenBudgetAllocation(200, 2_048, true),
                (options, allocation) -> new GenerationOptions(0.2, 2_048, List.of("STOP")));

        DynamicGenerationOptionsResult result = factory.createWithDiagnostics(
            existing, CAPABILITY, new TokenBudget(16_384, 0, 2_048), "prompt");

        assertEquals(new GenerationOptions(0.2, 2_048, List.of("STOP")), result.generationOptions());
        assertEquals(200, result.tokenBudgetDecision().inputTokens());
        assertEquals(2_048, result.tokenBudgetDecision().allocatedOutputTokens());
        assertTrue(result.tokenBudgetDecision().truncated());
        assertTrue(result.tokenBudgetDecision().cappedByApplicationLimit());
        assertFalse(result.tokenBudgetDecision().cappedByRequestLimit());
        assertTrue(result.warning());
    }

    @Test
    void reportsRequestCapAndKeepsNullRequestUncapped() {
        DynamicGenerationOptionsFactory factory = new DynamicGenerationOptionsFactory(
                (capability, budget, prompt) -> new TokenBudgetAllocation(100, 1_000, false),
                (options, allocation) -> new GenerationOptions(0.2, 1_000, List.of("STOP")));

        DynamicGenerationOptionsResult capped = factory.createWithDiagnostics(
            new GenerationOptions(0.7, 0, List.of()), CAPABILITY,
            new TokenBudget(16_384, 0, 2_048), "prompt");
        DynamicGenerationOptionsResult uncapped = factory.createWithDiagnostics(
            new GenerationOptions(0.7, null, List.of()), CAPABILITY,
            new TokenBudget(16_384, 0, 2_048), "prompt");

        assertTrue(capped.tokenBudgetDecision().cappedByRequestLimit());
        assertEquals(0, capped.generationOptions().maxTokens());
        assertFalse(uncapped.tokenBudgetDecision().cappedByRequestLimit());
        assertEquals(1_000, uncapped.generationOptions().maxTokens());
    }

    @Test
    void rejectsNullDependenciesAndInputs() {
        assertThrows(NullPointerException.class,
                () -> new DynamicGenerationOptionsFactory(null, (options, allocation) -> options));
        DynamicGenerationOptionsFactory factory = new DynamicGenerationOptionsFactory(
                (capability, budget, prompt) -> new TokenBudgetAllocation(1, 1, false),
                (options, allocation) -> options == null
                        ? new GenerationOptions(null, 1, List.of()) : options);
        assertThrows(NullPointerException.class, () -> factory.create(null, null, BUDGET, "prompt"));
        assertThrows(NullPointerException.class, () -> factory.create(null, CAPABILITY, null, "prompt"));
        assertThrows(NullPointerException.class, () -> factory.create(null, CAPABILITY, BUDGET, null));
    }
}
