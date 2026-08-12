package com.minikun.tokenbudget.domain;

public record TokenBudget(
        long maxContextTokens,
        long reservedOutputTokens,
        long applicationMaxOutputTokens) {
    public TokenBudget {
        if (maxContextTokens < 0) {
            throw new IllegalArgumentException("max context tokens must not be negative");
        }
        if (reservedOutputTokens < 0) {
            throw new IllegalArgumentException("reserved output tokens must not be negative");
        }
        if (applicationMaxOutputTokens < 0) {
            throw new IllegalArgumentException("application max output tokens must not be negative");
        }
    }
}
