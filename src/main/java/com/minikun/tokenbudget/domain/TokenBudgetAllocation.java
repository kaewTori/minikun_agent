package com.minikun.tokenbudget.domain;

public record TokenBudgetAllocation(
        long inputTokens,
        long availableOutputTokens,
        boolean truncated) {
    public TokenBudgetAllocation {
        if (inputTokens < 0) {
            throw new IllegalArgumentException("input tokens must not be negative");
        }
        if (availableOutputTokens < 0) {
            throw new IllegalArgumentException("available output tokens must not be negative");
        }
    }
}
