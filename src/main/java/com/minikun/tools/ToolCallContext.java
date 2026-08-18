package com.minikun.tools;

import java.util.Objects;

import com.minikun.agent.minikun_agent.conversation.ConversationId;

public record ToolCallContext(ConversationId conversationId, String callId, String ownerId) {
    public ToolCallContext(ConversationId conversationId, String callId) {
        this(conversationId, callId, "default");
    }

    public ToolCallContext {
        Objects.requireNonNull(conversationId, "conversation id must not be null");
        Objects.requireNonNull(callId, "tool call id must not be null");
        Objects.requireNonNull(ownerId, "owner id must not be null");
        if (callId.isBlank()) {
            throw new IllegalArgumentException("tool call id must not be blank");
        }
        if (ownerId.isBlank() || "*".equals(ownerId)) {
            throw new IllegalArgumentException("owner id must not be blank or wildcard");
        }
    }
}
