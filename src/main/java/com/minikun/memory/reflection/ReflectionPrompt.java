package com.minikun.memory.reflection;

import java.util.Objects;

import com.minikun.memory.model.CompletedConversation;

public record ReflectionPrompt(CompletedConversation conversation, String content) {
    public ReflectionPrompt {
        Objects.requireNonNull(conversation, "conversation must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}