package com.minikun.memory.model;

import java.util.List;
import java.util.Objects;

public record CompletedConversation(String ownerId, String conversationId, List<Message> messages) {
    public CompletedConversation(String conversationId, List<Message> messages) {
        this(null, conversationId, messages);
    }

    public CompletedConversation {
        if (ownerId != null && (ownerId.isBlank() || "*".equals(ownerId))) {
            throw new IllegalArgumentException("owner id must not be blank or wildcard");
        }
        Objects.requireNonNull(conversationId, "conversation id must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        if (conversationId.isBlank()) {
            throw new IllegalArgumentException("conversation id must not be blank");
        }
        messages = List.copyOf(messages);
    }

    public record Message(String role, String content) {
        public Message {
            Objects.requireNonNull(role, "message role must not be null");
            Objects.requireNonNull(content, "message content must not be null");
            if (role.isBlank() || content.isBlank()) {
                throw new IllegalArgumentException("message role and content must not be blank");
            }
        }
    }
}
