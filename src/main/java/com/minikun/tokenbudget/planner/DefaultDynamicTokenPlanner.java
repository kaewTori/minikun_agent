package com.minikun.tokenbudget.planner;

import com.minikun.model.capability.ModelCapability;
import com.minikun.tokenbudget.counter.TokenCounter;
import com.minikun.tokenbudget.domain.TokenBudget;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;
import com.minikun.tokenbudget.policy.TokenBudgetPolicy;

import java.util.Objects;

public final class DefaultDynamicTokenPlanner implements DynamicTokenPlanner {
    private final TokenCounter tokenCounter;
    private final TokenBudgetPolicy tokenBudgetPolicy;

    public DefaultDynamicTokenPlanner(TokenCounter tokenCounter, TokenBudgetPolicy tokenBudgetPolicy) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "token counter must not be null");
        this.tokenBudgetPolicy = Objects.requireNonNull(tokenBudgetPolicy, "token budget policy must not be null");
    }

    @Override
    public TokenBudgetAllocation plan(ModelCapability capability, TokenBudget budget, String prompt) {
        Objects.requireNonNull(capability, "model capability must not be null");
        Objects.requireNonNull(budget, "token budget must not be null");
        Objects.requireNonNull(prompt, "prompt must not be null");

        long inputTokens = tokenCounter.count(prompt);
        long modelOutputLimit = capability.maxOutputTokens();
        long effectiveApplicationMaximum = Math.min(
                budget.applicationMaxOutputTokens(), modelOutputLimit);
        TokenBudget effectiveBudget = new TokenBudget(
                Math.min(budget.maxContextTokens(), capability.contextWindowTokens()),
                budget.reservedOutputTokens(),
                effectiveApplicationMaximum);
        return tokenBudgetPolicy.allocate(effectiveBudget, inputTokens);
    }
}
