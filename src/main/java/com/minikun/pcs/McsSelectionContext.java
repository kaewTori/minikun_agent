package com.minikun.pcs;

import java.util.Objects;

public record McsSelectionContext(
        String currentUserMessage,
        String conversationHistory,
        ConversationAttributes conversationAttributes,
    RuntimeAttributes runtimeAttributes,
    MemorySelectionSignals memorySelectionSignals) {
    public McsSelectionContext(String currentUserMessage, String conversationHistory) {
        this(currentUserMessage, conversationHistory,
        ConversationAttributes.EMPTY, RuntimeAttributes.EMPTY,
        MemorySelectionSignals.EMPTY);
    }

    public McsSelectionContext(
        String currentUserMessage,
        String conversationHistory,
        ConversationAttributes conversationAttributes,
        RuntimeAttributes runtimeAttributes) {
    this(currentUserMessage, conversationHistory, conversationAttributes, runtimeAttributes,
        MemorySelectionSignals.EMPTY);
    }

    public McsSelectionContext {
        currentUserMessage = Objects.requireNonNullElse(currentUserMessage, "");
        conversationHistory = Objects.requireNonNullElse(conversationHistory, "");
        conversationAttributes = Objects.requireNonNullElse(
                conversationAttributes, ConversationAttributes.EMPTY);
        runtimeAttributes = Objects.requireNonNullElse(runtimeAttributes, RuntimeAttributes.EMPTY);
        memorySelectionSignals = Objects.requireNonNullElse(
            memorySelectionSignals, MemorySelectionSignals.EMPTY);
    }
}
