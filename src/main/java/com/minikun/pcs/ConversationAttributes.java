package com.minikun.pcs;

import java.util.Objects;

public record ConversationAttributes(String conversationId, int messageCount, boolean firstMessage) {
    public static final ConversationAttributes EMPTY = new ConversationAttributes("", 0, false);

    public ConversationAttributes {
        conversationId = Objects.requireNonNullElse(conversationId, "");
        if (messageCount < 0) {
            throw new IllegalArgumentException("messageCount must not be negative");
        }
    }
}
