package com.minikun.tokenbudget.diagnostics;

import java.util.Objects;

public final class DefaultTokenBudgetSafetyPolicy implements TokenBudgetSafetyPolicy {
    private static final long MINIMUM_OUTPUT_TOKENS = 256L;

    @Override
    public boolean shouldWarn(TokenBudgetDecision decision) {
        Objects.requireNonNull(decision, "token budget decision must not be null");
        return decision.allocatedOutputTokens() < MINIMUM_OUTPUT_TOKENS || decision.truncated();
    }
}