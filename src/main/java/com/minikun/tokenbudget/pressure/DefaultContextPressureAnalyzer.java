package com.minikun.tokenbudget.pressure;

import com.minikun.model.capability.ModelCapability;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;

import java.util.Objects;

public final class DefaultContextPressureAnalyzer implements ContextPressureAnalyzer {
    private static final long NORMAL_OUTPUT_THRESHOLD = 1_024L;
    private static final long WARNING_OUTPUT_THRESHOLD = 256L;

    @Override
    public ContextPressureDecision analyze(
            ModelCapability capability,
            TokenBudgetAllocation allocation) {
        Objects.requireNonNull(capability, "model capability must not be null");
        Objects.requireNonNull(allocation, "token budget allocation must not be null");

        long availableOutputTokens = allocation.availableOutputTokens();
        ContextPressureLevel level = levelFor(availableOutputTokens);
        return new ContextPressureDecision(
                allocation.inputTokens(), availableOutputTokens, level, level != ContextPressureLevel.NORMAL);
    }

    private ContextPressureLevel levelFor(long availableOutputTokens) {
        if (availableOutputTokens >= NORMAL_OUTPUT_THRESHOLD) {
            return ContextPressureLevel.NORMAL;
        }
        if (availableOutputTokens >= WARNING_OUTPUT_THRESHOLD) {
            return ContextPressureLevel.WARNING;
        }
        return ContextPressureLevel.CRITICAL;
    }
}