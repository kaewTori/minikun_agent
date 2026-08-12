package com.minikun.tokenbudget.runtime;

import com.minikun.model.ChatModelId;
import com.minikun.model.GenerationOptions;
import com.minikun.model.capability.ModelCapability;
import com.minikun.model.capability.ModelRole;
import com.minikun.tokenbudget.diagnostics.DefaultTokenBudgetSafetyPolicy;
import com.minikun.tokenbudget.diagnostics.TokenBudgetDecisionMapper;
import com.minikun.tokenbudget.domain.TokenBudget;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;
import com.minikun.tokenbudget.integration.GenerationOptionsResolver;
import com.minikun.tokenbudget.planner.DynamicTokenPlanner;
import com.minikun.tokenbudget.pressure.ContextPressureAnalyzer;
import com.minikun.tokenbudget.pressure.ContextPressureDecision;
import com.minikun.tokenbudget.pressure.ContextPressureLevel;
import com.minikun.tokenbudget.pressure.ContextPressureRecoveryPolicy;
import com.minikun.tokenbudget.pressure.DefaultContextPressureAnalyzer;
import com.minikun.tokenbudget.pressure.DefaultContextPressureRecoveryPolicy;
import com.minikun.tokenbudget.pressure.RecoveryAction;
import com.minikun.tokenbudget.recovery.ContextRecoveryPlanner;
import com.minikun.tokenbudget.recovery.ContextRecoveryPlan;
import com.minikun.tokenbudget.recovery.RecoveryPriority;
import com.minikun.tokenbudget.recovery.RecoveryTarget;
import com.minikun.tokenbudget.recovery.ContextRecoveryPriorityPolicy;
import com.minikun.tokenbudget.recovery.DefaultContextRecoveryPriorityPolicy;
import com.minikun.tokenbudget.recovery.DefaultContextRecoveryPlanner;
import com.minikun.tokenbudget.recovery.RecoveryPriorityPlan;
import com.minikun.tokenbudget.recovery.RecoveryStep;
import com.minikun.tokenbudget.recovery.RecoveryExecutionResult;
import com.minikun.tokenbudget.recovery.RecoveryExecutionStatus;
import com.minikun.tokenbudget.recovery.RecoveryExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

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
        void attachesPressureDiagnosticsWithoutChangingGenerationOptions() {
        AtomicReference<ModelCapability> receivedCapability = new AtomicReference<>();
        AtomicReference<TokenBudgetAllocation> receivedAllocation = new AtomicReference<>();
        GenerationOptions existing = new GenerationOptions(0.7, 2_000, List.of("END"));
        GenerationOptions resolved = new GenerationOptions(0.2, 500, List.of("STOP"));
        ContextPressureAnalyzer analyzer = (capability, allocation) -> {
            receivedCapability.set(capability);
            receivedAllocation.set(allocation);
            return new ContextPressureDecision(300, 500, ContextPressureLevel.WARNING, true);
        };
        ContextPressureRecoveryPolicy recoveryPolicy = decision -> RecoveryAction.REDUCE_CONTEXT;
        DynamicGenerationOptionsFactory factory = new DynamicGenerationOptionsFactory(
            (capability, budget, prompt) -> new TokenBudgetAllocation(300, 500, false),
            (options, allocation) -> resolved,
            new TokenBudgetDecisionMapper(),
            new DefaultTokenBudgetSafetyPolicy(),
            analyzer,
            recoveryPolicy);

        DynamicGenerationOptionsResult result = factory.createWithDiagnostics(
            existing, CAPABILITY, BUDGET, "prompt");

        assertSame(CAPABILITY, receivedCapability.get());
        assertEquals(new TokenBudgetAllocation(300, 500, false), receivedAllocation.get());
        assertSame(resolved, result.generationOptions());
        assertEquals(ContextPressureLevel.WARNING,
            result.contextPressureDiagnostics().decision().level());
        assertEquals(RecoveryAction.REDUCE_CONTEXT,
            result.contextPressureDiagnostics().recoveryAction());
        }

        @Test
        void attachesRecoveryPlanWithoutChangingGenerationOptions() {
        GenerationOptions existing = new GenerationOptions(0.7, 2_000, List.of("END"));
        GenerationOptions resolved = new GenerationOptions(0.2, 500, List.of("STOP"));
        ContextRecoveryPlanner planner = decision -> new ContextRecoveryPlan(
            RecoveryPriority.MEDIUM, RecoveryTarget.KNOWLEDGE_CONTEXT, true);
        DynamicGenerationOptionsFactory factory = new DynamicGenerationOptionsFactory(
            (capability, budget, prompt) -> new TokenBudgetAllocation(300, 500, false),
            (options, allocation) -> resolved,
            new TokenBudgetDecisionMapper(),
            new DefaultTokenBudgetSafetyPolicy(),
            new DefaultContextPressureAnalyzer(),
            new DefaultContextPressureRecoveryPolicy(),
            planner);

        DynamicGenerationOptionsResult result = factory.createWithDiagnostics(
            existing, CAPABILITY, BUDGET, "prompt");

        assertSame(resolved, result.generationOptions());
        assertEquals(RecoveryPriority.MEDIUM,
            result.contextPressureDiagnostics().recoveryPlan().priority());
        assertEquals(RecoveryTarget.KNOWLEDGE_CONTEXT,
            result.contextPressureDiagnostics().recoveryPlan().target());
        assertTrue(result.contextPressureDiagnostics().recoveryPlan().required());
        }

        @Test
        void attachesPriorityPlanWithoutChangingGenerationOptions() {
        GenerationOptions existing = new GenerationOptions(0.7, 2_000, List.of("END"));
        GenerationOptions resolved = new GenerationOptions(0.2, 500, List.of("STOP"));
        ContextRecoveryPriorityPolicy priorityPolicy = plan -> new RecoveryPriorityPlan(
            List.of(RecoveryStep.REDUCE_KNOWLEDGE));
        DynamicGenerationOptionsFactory factory = new DynamicGenerationOptionsFactory(
            (capability, budget, prompt) -> new TokenBudgetAllocation(300, 500, false),
            (options, allocation) -> resolved,
            new TokenBudgetDecisionMapper(),
            new DefaultTokenBudgetSafetyPolicy(),
            new DefaultContextPressureAnalyzer(),
            new DefaultContextPressureRecoveryPolicy(),
            new DefaultContextRecoveryPlanner(),
            priorityPolicy);

        DynamicGenerationOptionsResult result = factory.createWithDiagnostics(
            existing, CAPABILITY, BUDGET, "prompt");

        assertSame(resolved, result.generationOptions());
        assertEquals(List.of(RecoveryStep.REDUCE_KNOWLEDGE),
            result.contextPressureDiagnostics().priorityPlan().steps());
        }

        @Test
        void attachesExecutionResultWithoutChangingGenerationOptions() {
        GenerationOptions existing = new GenerationOptions(0.7, 2_000, List.of("END"));
        GenerationOptions resolved = new GenerationOptions(0.2, 500, List.of("STOP"));
        RecoveryExecutor executor = plan -> new RecoveryExecutionResult(
            RecoveryExecutionStatus.PLANNED, plan, false);
        DynamicGenerationOptionsFactory factory = new DynamicGenerationOptionsFactory(
            (capability, budget, prompt) -> new TokenBudgetAllocation(300, 500, false),
            (options, allocation) -> resolved,
            new TokenBudgetDecisionMapper(),
            new DefaultTokenBudgetSafetyPolicy(),
            new DefaultContextPressureAnalyzer(),
            new DefaultContextPressureRecoveryPolicy(),
            new DefaultContextRecoveryPlanner(),
            new DefaultContextRecoveryPriorityPolicy(),
            executor);

        DynamicGenerationOptionsResult result = factory.createWithDiagnostics(
            existing, CAPABILITY, BUDGET, "prompt");

        assertSame(resolved, result.generationOptions());
        assertEquals(RecoveryExecutionStatus.PLANNED,
            result.contextPressureDiagnostics().executionResult().status());
        assertFalse(result.contextPressureDiagnostics().executionResult().changed());
        }

    @Test
    void createDoesNotInvokeRecoveryExecutor() {
        AtomicBoolean executorCalled = new AtomicBoolean();
        RecoveryExecutor executor = plan -> {
            executorCalled.set(true);
            return new RecoveryExecutionResult(RecoveryExecutionStatus.PLANNED, plan, false);
        };
        DynamicGenerationOptionsFactory factory = new DynamicGenerationOptionsFactory(
                (capability, budget, prompt) -> new TokenBudgetAllocation(300, 500, false),
                (options, allocation) -> new GenerationOptions(0.2, 500, List.of("STOP")),
                new TokenBudgetDecisionMapper(),
                new DefaultTokenBudgetSafetyPolicy(),
                new DefaultContextPressureAnalyzer(),
                new DefaultContextPressureRecoveryPolicy(),
                new DefaultContextRecoveryPlanner(),
                new DefaultContextRecoveryPriorityPolicy(),
                executor);

        factory.create(new GenerationOptions(0.7, 2_000, List.of("END")), CAPABILITY, BUDGET, "prompt");

        assertFalse(executorCalled.get());
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
