package com.minikun.tokenbudget.runtime;

import com.minikun.model.GenerationOptions;
import com.minikun.tokenbudget.diagnostics.ContextPressureDiagnostics;
import com.minikun.tokenbudget.diagnostics.TokenBudgetDecision;

import java.util.Objects;

public record DynamicGenerationOptionsResult(
        GenerationOptions generationOptions,
        TokenBudgetDecision tokenBudgetDecision,
        boolean warning,
        ContextPressureDiagnostics contextPressureDiagnostics) {
    public DynamicGenerationOptionsResult(
            GenerationOptions generationOptions,
            TokenBudgetDecision tokenBudgetDecision,
            boolean warning) {
        this(generationOptions, tokenBudgetDecision, warning, null);
    }

    public DynamicGenerationOptionsResult {
        Objects.requireNonNull(generationOptions, "generation options must not be null");
        Objects.requireNonNull(tokenBudgetDecision, "token budget decision must not be null");
    }
}