package com.minikun.tokenbudget.integration;

import com.minikun.model.GenerationOptions;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;

import java.util.List;
import java.util.Objects;

public final class DefaultGenerationOptionsResolver implements GenerationOptionsResolver {
    private static final long MAX_INTEGER_VALUE = Integer.MAX_VALUE;

    @Override
    public GenerationOptions resolve(GenerationOptions existing, TokenBudgetAllocation allocation) {
        Objects.requireNonNull(allocation, "token budget allocation must not be null");
        long availableOutputTokens = allocation.availableOutputTokens();
        if (availableOutputTokens > MAX_INTEGER_VALUE) {
            throw new IllegalArgumentException("available output tokens exceed integer max value");
        }

        Integer maxTokens = (int) availableOutputTokens;
        if (existing == null) {
            return new GenerationOptions(null, maxTokens, List.of());
        }
        return new GenerationOptions(existing.temperature(), maxTokens, existing.stop());
    }
}
