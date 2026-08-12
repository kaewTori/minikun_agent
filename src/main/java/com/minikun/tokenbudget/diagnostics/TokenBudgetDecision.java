package com.minikun.tokenbudget.diagnostics;

public record TokenBudgetDecision(
        long inputTokens,
        long allocatedOutputTokens,
        boolean truncated,
        boolean cappedByApplicationLimit,
        boolean cappedByRequestLimit) {
    public TokenBudgetDecision {
        if (inputTokens < 0) {
            throw new IllegalArgumentException("input tokens must not be negative");
        }
        if (allocatedOutputTokens < 0) {
            throw new IllegalArgumentException("allocated output tokens must not be negative");
        }
    }
}