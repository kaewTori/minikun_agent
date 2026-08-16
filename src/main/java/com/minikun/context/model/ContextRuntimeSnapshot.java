package com.minikun.context.model;

import java.util.Objects;
import java.util.EnumMap;
import java.util.Map;

/** Immutable accounting snapshot for one personal-agent context preparation. */
public record ContextRuntimeSnapshot(
        long requestedContextCharacters,
        long finalPromptCharacters,
        int recoveryAttempts,
        Long estimatedInputTokens,
        Long allocatedOutputTokens,
        Map<ContextSource, Long> sourceCharacters) {
    public ContextRuntimeSnapshot {
        if (requestedContextCharacters < 0 || finalPromptCharacters < 0) {
            throw new IllegalArgumentException("context character counts must not be negative");
        }
        if (recoveryAttempts < 0) {
            throw new IllegalArgumentException("recovery attempts must not be negative");
        }
        if (estimatedInputTokens != null && estimatedInputTokens < 0) {
            throw new IllegalArgumentException("estimated input tokens must not be negative");
        }
        if (allocatedOutputTokens != null && allocatedOutputTokens < 0) {
            throw new IllegalArgumentException("allocated output tokens must not be negative");
        }
        Objects.requireNonNull(sourceCharacters, "source characters must not be null");
        EnumMap<ContextSource, Long> copy = new EnumMap<>(ContextSource.class);
        sourceCharacters.forEach((source, characters) -> {
            Objects.requireNonNull(source, "context source must not be null");
            Objects.requireNonNull(characters, "source character count must not be null");
            if (characters < 0) {
                throw new IllegalArgumentException("source character counts must not be negative");
            }
            copy.put(source, characters);
        });
        sourceCharacters = Map.copyOf(copy);
    }

    public long sourceCharacters(ContextSource source) {
        Objects.requireNonNull(source, "context source must not be null");
        return sourceCharacters.getOrDefault(source, 0L);
    }
}
