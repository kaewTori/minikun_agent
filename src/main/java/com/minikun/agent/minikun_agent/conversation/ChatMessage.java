package com.minikun.agent.minikun_agent.conversation;

import java.util.Objects;

public record ChatMessage(String role, String content) {
    public ChatMessage {
        Objects.requireNonNull(role, "message role must not be null");
        Objects.requireNonNull(content, "message content must not be null");
        if (role.isBlank()) {
            throw new IllegalArgumentException("message role must not be blank");
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("message content must not be blank");
        }
    }
}
