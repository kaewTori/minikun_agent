package com.minikun.pcs;

import java.util.Objects;

public record McsSelectionContext(
        String currentUserMessage,
        String conversationHistory,
        ConversationAttributes conversationAttributes,
        RuntimeAttributes runtimeAttributes) {
    public McsSelectionContext(String currentUserMessage, String conversationHistory) {
        this(currentUserMessage, conversationHistory,
                ConversationAttributes.EMPTY, RuntimeAttributes.EMPTY);
    }

    public McsSelectionContext {
        currentUserMessage = Objects.requireNonNullElse(currentUserMessage, "");
        conversationHistory = Objects.requireNonNullElse(conversationHistory, "");
        conversationAttributes = Objects.requireNonNullElse(
                conversationAttributes, ConversationAttributes.EMPTY);
        runtimeAttributes = Objects.requireNonNullElse(runtimeAttributes, RuntimeAttributes.EMPTY);
    }
}
