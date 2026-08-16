package com.minikun.context.runtime;

import com.minikun.model.GenerationOptions;
import com.minikun.context.model.ContextRuntimeSnapshot;
import com.minikun.pcs.model.Prompt;
import com.minikun.tokenbudget.runtime.DynamicGenerationOptionsResult;

import java.util.Objects;
import java.util.Optional;

/** Result of preparing one model request through the personal context pipeline. */
public record PersonalContextRuntimeResult(
        Prompt prompt,
        GenerationOptions generationOptions,
        Optional<DynamicGenerationOptionsResult> budgetResult,
        ContextRuntimeSnapshot snapshot) {
    public PersonalContextRuntimeResult {
        Objects.requireNonNull(prompt, "prompt must not be null");
        Objects.requireNonNull(generationOptions, "generation options must not be null");
        budgetResult = Objects.requireNonNull(budgetResult, "budget result must not be null");
        Objects.requireNonNull(snapshot, "context snapshot must not be null");
    }
}
