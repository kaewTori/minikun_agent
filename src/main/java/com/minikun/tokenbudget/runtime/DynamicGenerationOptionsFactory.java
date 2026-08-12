package com.minikun.tokenbudget.runtime;

import com.minikun.model.GenerationOptions;
import com.minikun.model.capability.ModelCapability;
import com.minikun.tokenbudget.domain.TokenBudget;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;
import com.minikun.tokenbudget.integration.GenerationOptionsResolver;
import com.minikun.tokenbudget.planner.DynamicTokenPlanner;

import java.util.Objects;

public final class DynamicGenerationOptionsFactory {
    private final DynamicTokenPlanner dynamicTokenPlanner;
    private final GenerationOptionsResolver generationOptionsResolver;

    public DynamicGenerationOptionsFactory(
            DynamicTokenPlanner dynamicTokenPlanner,
            GenerationOptionsResolver generationOptionsResolver) {
        this.dynamicTokenPlanner = Objects.requireNonNull(dynamicTokenPlanner,
                "dynamic token planner must not be null");
        this.generationOptionsResolver = Objects.requireNonNull(generationOptionsResolver,
                "generation options resolver must not be null");
    }

    public GenerationOptions create(
            GenerationOptions existing,
            ModelCapability capability,
            TokenBudget budget,
            String prompt) {
        Objects.requireNonNull(capability, "model capability must not be null");
        Objects.requireNonNull(budget, "token budget must not be null");
        Objects.requireNonNull(prompt, "prompt must not be null");

        TokenBudgetAllocation allocation = dynamicTokenPlanner.plan(capability, budget, prompt);
        GenerationOptions resolved = generationOptionsResolver.resolve(existing, allocation);
        return applyRequestMaximum(existing, resolved);
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
