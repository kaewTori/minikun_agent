package com.minikun.tokenbudget.runtime;

import com.minikun.model.GenerationOptions;
import com.minikun.tokenbudget.diagnostics.TokenBudgetDecision;
import com.minikun.tokenbudget.pressure.ContextPressureLevel;
import java.util.Objects;

public record DynamicGenerationOptionsResult(
        GenerationOptions generationOptions,
        TokenBudgetDecision tokenBudgetDecision,
        ContextPressureLevel pressureLevel) {
    public DynamicGenerationOptionsResult {
        Objects.requireNonNull(generationOptions, "generation options must not be null");
        Objects.requireNonNull(tokenBudgetDecision, "token budget decision must not be null");
        Objects.requireNonNull(pressureLevel, "context pressure level must not be null");
    }
}
