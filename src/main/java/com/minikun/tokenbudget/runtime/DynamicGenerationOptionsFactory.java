package com.minikun.tokenbudget.runtime;

import com.minikun.model.GenerationOptions;
import com.minikun.model.capability.ModelCapability;
import com.minikun.tokenbudget.diagnostics.DefaultTokenBudgetSafetyPolicy;
import com.minikun.tokenbudget.diagnostics.ContextPressureDiagnostics;
import com.minikun.tokenbudget.diagnostics.TokenBudgetDecisionMapper;
import com.minikun.tokenbudget.diagnostics.TokenBudgetSafetyPolicy;
import com.minikun.tokenbudget.domain.TokenBudget;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;
import com.minikun.tokenbudget.integration.GenerationOptionsResolver;
import com.minikun.tokenbudget.planner.DynamicTokenPlanner;
import com.minikun.tokenbudget.pressure.ContextPressureAnalyzer;
import com.minikun.tokenbudget.pressure.ContextPressureRecoveryPolicy;
import com.minikun.tokenbudget.pressure.DefaultContextPressureAnalyzer;
import com.minikun.tokenbudget.pressure.DefaultContextPressureRecoveryPolicy;
import com.minikun.tokenbudget.recovery.ContextRecoveryPlanner;
import com.minikun.tokenbudget.recovery.DefaultContextRecoveryPlanner;
import com.minikun.tokenbudget.recovery.ContextRecoveryPriorityPolicy;
import com.minikun.tokenbudget.recovery.DefaultContextRecoveryPriorityPolicy;
import com.minikun.tokenbudget.recovery.NoOpRecoveryExecutor;
import com.minikun.tokenbudget.recovery.RecoveryExecutionResult;
import com.minikun.tokenbudget.recovery.RecoveryExecutor;

import java.util.Objects;

public final class DynamicGenerationOptionsFactory {
    private final DynamicTokenPlanner dynamicTokenPlanner;
    private final GenerationOptionsResolver generationOptionsResolver;
    private final TokenBudgetDecisionMapper tokenBudgetDecisionMapper;
    private final TokenBudgetSafetyPolicy tokenBudgetSafetyPolicy;
    private final ContextPressureAnalyzer contextPressureAnalyzer;
    private final ContextPressureRecoveryPolicy contextPressureRecoveryPolicy;
    private final ContextRecoveryPlanner contextRecoveryPlanner;
    private final ContextRecoveryPriorityPolicy contextRecoveryPriorityPolicy;
    private final RecoveryExecutor recoveryExecutor;

    public DynamicGenerationOptionsFactory(
            DynamicTokenPlanner dynamicTokenPlanner,
            GenerationOptionsResolver generationOptionsResolver) {
        this.dynamicTokenPlanner = Objects.requireNonNull(dynamicTokenPlanner,
                "dynamic token planner must not be null");
        this.generationOptionsResolver = Objects.requireNonNull(generationOptionsResolver,
                "generation options resolver must not be null");
        this.tokenBudgetDecisionMapper = new TokenBudgetDecisionMapper();
        this.tokenBudgetSafetyPolicy = new DefaultTokenBudgetSafetyPolicy();
        this.contextPressureAnalyzer = new DefaultContextPressureAnalyzer();
        this.contextPressureRecoveryPolicy = new DefaultContextPressureRecoveryPolicy();
        this.contextRecoveryPlanner = new DefaultContextRecoveryPlanner();
        this.contextRecoveryPriorityPolicy = new DefaultContextRecoveryPriorityPolicy();
        this.recoveryExecutor = new NoOpRecoveryExecutor();
        }

        public DynamicGenerationOptionsFactory(
            DynamicTokenPlanner dynamicTokenPlanner,
            GenerationOptionsResolver generationOptionsResolver,
            TokenBudgetDecisionMapper tokenBudgetDecisionMapper,
            TokenBudgetSafetyPolicy tokenBudgetSafetyPolicy) {
            this(dynamicTokenPlanner, generationOptionsResolver, tokenBudgetDecisionMapper,
                tokenBudgetSafetyPolicy, new DefaultContextPressureAnalyzer(),
                new DefaultContextPressureRecoveryPolicy());
            }

            public DynamicGenerationOptionsFactory(
                DynamicTokenPlanner dynamicTokenPlanner,
                GenerationOptionsResolver generationOptionsResolver,
                TokenBudgetDecisionMapper tokenBudgetDecisionMapper,
                TokenBudgetSafetyPolicy tokenBudgetSafetyPolicy,
                ContextPressureAnalyzer contextPressureAnalyzer,
                    ContextPressureRecoveryPolicy contextPressureRecoveryPolicy) {
                this(dynamicTokenPlanner, generationOptionsResolver, tokenBudgetDecisionMapper,
                    tokenBudgetSafetyPolicy, contextPressureAnalyzer, contextPressureRecoveryPolicy,
                    new DefaultContextRecoveryPlanner());
                }

                public DynamicGenerationOptionsFactory(
                    DynamicTokenPlanner dynamicTokenPlanner,
                    GenerationOptionsResolver generationOptionsResolver,
                    TokenBudgetDecisionMapper tokenBudgetDecisionMapper,
                    TokenBudgetSafetyPolicy tokenBudgetSafetyPolicy,
                    ContextPressureAnalyzer contextPressureAnalyzer,
                    ContextPressureRecoveryPolicy contextPressureRecoveryPolicy,
                    ContextRecoveryPlanner contextRecoveryPlanner) {
                    this(dynamicTokenPlanner, generationOptionsResolver, tokenBudgetDecisionMapper,
                        tokenBudgetSafetyPolicy, contextPressureAnalyzer, contextPressureRecoveryPolicy,
                        contextRecoveryPlanner, new DefaultContextRecoveryPriorityPolicy());
                    }

                    public DynamicGenerationOptionsFactory(
                        DynamicTokenPlanner dynamicTokenPlanner,
                        GenerationOptionsResolver generationOptionsResolver,
                        TokenBudgetDecisionMapper tokenBudgetDecisionMapper,
                        TokenBudgetSafetyPolicy tokenBudgetSafetyPolicy,
                        ContextPressureAnalyzer contextPressureAnalyzer,
                        ContextPressureRecoveryPolicy contextPressureRecoveryPolicy,
                        ContextRecoveryPlanner contextRecoveryPlanner,
                        ContextRecoveryPriorityPolicy contextRecoveryPriorityPolicy) {
                    this(dynamicTokenPlanner, generationOptionsResolver, tokenBudgetDecisionMapper,
                        tokenBudgetSafetyPolicy, contextPressureAnalyzer, contextPressureRecoveryPolicy,
                        contextRecoveryPlanner, contextRecoveryPriorityPolicy, new NoOpRecoveryExecutor());
                    }

                    public DynamicGenerationOptionsFactory(
                        DynamicTokenPlanner dynamicTokenPlanner,
                        GenerationOptionsResolver generationOptionsResolver,
                        TokenBudgetDecisionMapper tokenBudgetDecisionMapper,
                        TokenBudgetSafetyPolicy tokenBudgetSafetyPolicy,
                        ContextPressureAnalyzer contextPressureAnalyzer,
                        ContextPressureRecoveryPolicy contextPressureRecoveryPolicy,
                        ContextRecoveryPlanner contextRecoveryPlanner,
                        ContextRecoveryPriorityPolicy contextRecoveryPriorityPolicy,
                        RecoveryExecutor recoveryExecutor) {
        this.dynamicTokenPlanner = Objects.requireNonNull(dynamicTokenPlanner,
            "dynamic token planner must not be null");
        this.generationOptionsResolver = Objects.requireNonNull(generationOptionsResolver,
            "generation options resolver must not be null");
        this.tokenBudgetDecisionMapper = Objects.requireNonNull(tokenBudgetDecisionMapper,
            "token budget decision mapper must not be null");
        this.tokenBudgetSafetyPolicy = Objects.requireNonNull(tokenBudgetSafetyPolicy,
            "token budget safety policy must not be null");
        this.contextPressureAnalyzer = Objects.requireNonNull(contextPressureAnalyzer,
            "context pressure analyzer must not be null");
        this.contextPressureRecoveryPolicy = Objects.requireNonNull(contextPressureRecoveryPolicy,
            "context pressure recovery policy must not be null");
        this.contextRecoveryPlanner = Objects.requireNonNull(contextRecoveryPlanner,
            "context recovery planner must not be null");
        this.contextRecoveryPriorityPolicy = Objects.requireNonNull(contextRecoveryPriorityPolicy,
            "context recovery priority policy must not be null");
        this.recoveryExecutor = Objects.requireNonNull(recoveryExecutor,
            "recovery executor must not be null");
    }

    public GenerationOptions create(
            GenerationOptions existing,
            ModelCapability capability,
            TokenBudget budget,
            String prompt) {
        Objects.requireNonNull(capability, "model capability must not be null");
        Objects.requireNonNull(budget, "token budget must not be null");
        Objects.requireNonNull(prompt, "prompt must not be null");

        return resolveGenerationOptions(existing, capability, budget, prompt).generationOptions();
        }

        public DynamicGenerationOptionsResult createWithDiagnostics(
            GenerationOptions existing,
            ModelCapability capability,
            TokenBudget budget,
            String prompt) {
        Objects.requireNonNull(capability, "model capability must not be null");
        Objects.requireNonNull(budget, "token budget must not be null");
        Objects.requireNonNull(prompt, "prompt must not be null");

        GenerationResolution resolution = resolveGenerationOptions(existing, capability, budget, prompt);
        GenerationOptions requestCapped = resolution.generationOptions();
        TokenBudgetAllocation allocation = resolution.allocation();
        TokenBudgetDecisionMapper.RuntimeMetadata metadata =
            new TokenBudgetDecisionMapper.RuntimeMetadata(budget.applicationMaxOutputTokens(),
                existing == null ? null : existing.maxTokens());
        var decision = tokenBudgetDecisionMapper.map(allocation, metadata);
        var pressureDecision = contextPressureAnalyzer.analyze(capability, allocation);
        var recoveryPlan = contextRecoveryPlanner.plan(pressureDecision);
        var priorityPlan = contextRecoveryPriorityPolicy.prioritize(recoveryPlan);
        RecoveryExecutionResult executionResult = recoveryExecutor.execute(priorityPlan);
        return new DynamicGenerationOptionsResult(
            requestCapped, decision, tokenBudgetSafetyPolicy.shouldWarn(decision),
            new ContextPressureDiagnostics(
            pressureDecision, contextPressureRecoveryPolicy.decide(pressureDecision), recoveryPlan,
                priorityPlan, executionResult));
    }

            private GenerationResolution resolveGenerationOptions(
                GenerationOptions existing,
                ModelCapability capability,
                TokenBudget budget,
                String prompt) {
            TokenBudgetAllocation allocation = dynamicTokenPlanner.plan(capability, budget, prompt);
            GenerationOptions resolved = generationOptionsResolver.resolve(existing, allocation);
                return new GenerationResolution(applyRequestMaximum(existing, resolved), allocation);
            }

            private record GenerationResolution(
                    GenerationOptions generationOptions,
                    TokenBudgetAllocation allocation) {
            }

    private GenerationOptions applyRequestMaximum(GenerationOptions existing, GenerationOptions resolved) {
        if (existing == null || existing.maxTokens() == null || resolved.maxTokens() == null) {
            return resolved;
        }
        if (existing.maxTokens() < 0) {
            throw new IllegalArgumentException("existing max tokens must not be negative");
        }
        int requestMaximum = existing.maxTokens();
        int resolvedMaximum = Math.min(requestMaximum, resolved.maxTokens());
        if (resolvedMaximum == resolved.maxTokens()) {
            return resolved;
        }
        return new GenerationOptions(resolved.temperature(), resolvedMaximum, resolved.stop());
    }
}
