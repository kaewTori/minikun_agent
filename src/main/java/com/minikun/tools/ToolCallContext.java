package com.minikun.tools;

import java.util.Objects;

import com.minikun.agent.minikun_agent.conversation.ConversationId;

public record ToolCallContext(ConversationId conversationId, String callId, String ownerId, String requestId) {
    public ToolCallContext(ConversationId conversationId, String callId) {
        this(conversationId, callId, "default", "");
    }

    public ToolCallContext(ConversationId conversationId, String callId, String ownerId) {
        this(conversationId, callId, ownerId, "");
    }

    public ToolCallContext {
        Objects.requireNonNull(conversationId, "conversation id must not be null");
        Objects.requireNonNull(callId, "tool call id must not be null");
        Objects.requireNonNull(ownerId, "owner id must not be null");
        requestId = requestId == null ? "" : requestId.strip();
        if (callId.isBlank()) {
            throw new IllegalArgumentException("tool call id must not be blank");
        }
        if (ownerId.isBlank() || "*".equals(ownerId)) {
            throw new IllegalArgumentException("owner id must not be blank or wildcard");
        }
        if (requestId.length() > 256) throw new IllegalArgumentException("request id must be at most 256 characters");
    }
}
