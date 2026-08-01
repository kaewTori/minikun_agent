package com.minikun.pcs.model;

import java.util.List;
import java.util.Objects;

public record Prompt(List<PromptMessage> messages) {
    public Prompt {
        Objects.requireNonNull(messages, "prompt messages must not be null");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("prompt must contain at least one message");
        }
        if (messages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("prompt messages must not contain null");
        }
        messages = List.copyOf(messages);
    }
}
