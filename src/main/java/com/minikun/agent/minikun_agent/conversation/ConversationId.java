package com.minikun.agent.minikun_agent.conversation;

import java.util.Objects;

public record ConversationId(String value) {
    public ConversationId {
        Objects.requireNonNull(value, "conversation id must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("conversation id must not be blank");
        }
    }
}
