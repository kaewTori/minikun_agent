package com.minikun.memory;

import java.util.Objects;

import com.minikun.agent.minikun_agent.conversation.ConversationId;

public record MemoryScope(String ownerId, ConversationId conversationId) {
    public MemoryScope {
        Objects.requireNonNull(ownerId, "owner id must not be null");
        if (ownerId.isBlank() || "*".equals(ownerId)) {
            throw new IllegalArgumentException("owner id must not be blank or wildcard");
        }
        Objects.requireNonNull(conversationId, "conversation id must not be null");
    }
}