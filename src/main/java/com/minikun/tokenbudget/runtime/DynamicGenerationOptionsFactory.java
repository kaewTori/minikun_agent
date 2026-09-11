package com.minikun.tokenbudget.runtime;

import com.minikun.model.GenerationOptions;
import com.minikun.model.capability.ModelCapability;
import com.minikun.tokenbudget.diagnostics.TokenBudgetDecision;
import com.minikun.tokenbudget.domain.TokenBudget;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;
import com.minikun.tokenbudget.integration.GenerationOptionsResolver;
import com.minikun.tokenbudget.planner.DynamicTokenPlanner;
import com.minikun.tokenbudget.pressure.ContextPressureLevel;
import java.util.Objects;

public final class DynamicGenerationOptionsFactory {
    private final DynamicTokenPlanner planner;
    private final GenerationOptionsResolver resolver;

    public DynamicGenerationOptionsFactory(DynamicTokenPlanner planner, GenerationOptionsResolver resolver) {
        this.planner = Objects.requireNonNull(planner, "dynamic token planner must not be null");
        this.resolver = Objects.requireNonNull(resolver, "generation options resolver must not be null");
    }

    public GenerationOptions create(
            GenerationOptions existing, ModelCapability capability, TokenBudget budget, String prompt) {
        return resolve(existing, capability, budget, prompt).options();
    }

    public DynamicGenerationOptionsResult createWithDiagnostics(
            GenerationOptions existing, ModelCapability capability, TokenBudget budget, String prompt) {
        Resolution result = resolve(existing, capability, budget, prompt);
        TokenBudgetAllocation allocation = result.allocation();
        return new DynamicGenerationOptionsResult(
                result.options(),
                new TokenBudgetDecision(
                        allocation.inputTokens(),
                        allocation.availableOutputTokens(),
                        allocation.truncated(),
                        allocation.availableOutputTokens() == budget.applicationMaxOutputTokens(),
                        existing != null && existing.maxTokens() != null
                                && allocation.availableOutputTokens() > existing.maxTokens()),
                pressure(allocation.availableOutputTokens(), desiredOutputTokens(existing, capability, budget)));
    }

    public long desiredOutputTokens(GenerationOptions existing, ModelCapability capability, TokenBudget budget) {
        long maximum = Math.min(capability.maxOutputTokens(), budget.applicationMaxOutputTokens());
        return existing == null || existing.maxTokens() == null
                ? maximum : Math.min(maximum, existing.maxTokens());
    }

    private Resolution resolve(
            GenerationOptions existing, ModelCapability capability, TokenBudget budget, String prompt) {
        Objects.requireNonNull(capability, "model capability must not be null");
        Objects.requireNonNull(budget, "budget must not be null");
        Objects.requireNonNull(prompt, "prompt must not be null");
        TokenBudgetAllocation allocation = planner.plan(capability, budget, prompt);
        return new Resolution(capRequest(existing, resolver.resolve(existing, allocation)), allocation);
    }

    private GenerationOptions capRequest(GenerationOptions existing, GenerationOptions resolved) {
        if (existing == null || existing.maxTokens() == null || resolved.maxTokens() == null) return resolved;
        if (existing.maxTokens() < 0) throw new IllegalArgumentException("existing max tokens must not be negative");
        int maximum = Math.min(existing.maxTokens(), resolved.maxTokens());
        return maximum == resolved.maxTokens() ? resolved
                : new GenerationOptions(resolved.temperature(), maximum, resolved.stop(), resolved.reasoning());
    }

    private ContextPressureLevel pressure(long availableOutputTokens, long desiredOutputTokens) {
        if (availableOutputTokens >= desiredOutputTokens) return ContextPressureLevel.NORMAL;
        if (availableOutputTokens >= 256) return ContextPressureLevel.WARNING;
        return ContextPressureLevel.CRITICAL;
    }

    private record Resolution(GenerationOptions options, TokenBudgetAllocation allocation) { }
}
