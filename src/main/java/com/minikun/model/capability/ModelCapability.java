package com.minikun.model.capability;

import com.minikun.model.ChatModelId;

import java.util.Objects;

public record ModelCapability(
        ChatModelId modelId,
        ModelRole role,
        long contextWindowTokens,
        long maxOutputTokens) {
    public ModelCapability {
        Objects.requireNonNull(modelId, "model id must not be null");
        Objects.requireNonNull(role, "model role must not be null");
        if (contextWindowTokens < 0) {
            throw new IllegalArgumentException("context window tokens must not be negative");
        }
        if (maxOutputTokens < 0) {
            throw new IllegalArgumentException("max output tokens must not be negative");
        }
    }
}
