package com.minikun.tokenbudget.diagnostics;

import com.minikun.tokenbudget.domain.TokenBudgetAllocation;

import java.util.Objects;

public final class TokenBudgetDecisionMapper {
    public TokenBudgetDecision map(
            TokenBudgetAllocation allocation,
            RuntimeMetadata runtimeMetadata) {
        Objects.requireNonNull(allocation, "token budget allocation must not be null");
        Objects.requireNonNull(runtimeMetadata, "token budget runtime metadata must not be null");
        return new TokenBudgetDecision(
                allocation.inputTokens(),
                allocation.availableOutputTokens(),
                allocation.truncated(),
                allocation.availableOutputTokens() == runtimeMetadata.applicationLimitTokens(),
                runtimeMetadata.requestLimitTokens() != null
                        && allocation.availableOutputTokens() > runtimeMetadata.requestLimitTokens());
    }

    public record RuntimeMetadata(long applicationLimitTokens, Integer requestLimitTokens) {
        public RuntimeMetadata {
            if (applicationLimitTokens < 0) {
                throw new IllegalArgumentException("application limit tokens must not be negative");
            }
            if (requestLimitTokens != null && requestLimitTokens < 0) {
                throw new IllegalArgumentException("request limit tokens must not be negative");
            }
        }
    }
}