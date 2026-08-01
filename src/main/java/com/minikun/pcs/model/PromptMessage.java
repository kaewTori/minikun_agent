package com.minikun.pcs.model;

import java.util.Objects;

public record PromptMessage(PromptRole role, String content) {
    public PromptMessage {
        Objects.requireNonNull(role, "prompt message role must not be null");
        Objects.requireNonNull(content, "prompt message content must not be null");
    }
}
