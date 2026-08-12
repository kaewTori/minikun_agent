package com.minikun.tokenbudget.policy;

import com.minikun.tokenbudget.domain.TokenBudget;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;

import java.math.BigInteger;
import java.util.Objects;

public final class DefaultTokenBudgetPolicy implements TokenBudgetPolicy {
    @Override
    public TokenBudgetAllocation allocate(TokenBudget budget, long inputTokens) {
        Objects.requireNonNull(budget, "budget must not be null");
        if (inputTokens < 0) {
            throw new IllegalArgumentException("input tokens must not be negative");
        }

        BigInteger availableOutput = BigInteger.valueOf(budget.maxContextTokens())
                .subtract(BigInteger.valueOf(inputTokens))
                .subtract(BigInteger.valueOf(budget.reservedOutputTokens()));
        if (availableOutput.signum() <= 0) {
            return new TokenBudgetAllocation(inputTokens, 0, true);
        }

        BigInteger applicationMaximum = BigInteger.valueOf(budget.applicationMaxOutputTokens());
        BigInteger allocatedOutput = availableOutput.min(applicationMaximum);
        return new TokenBudgetAllocation(inputTokens, allocatedOutput.longValueExact(), false);
    }
}
