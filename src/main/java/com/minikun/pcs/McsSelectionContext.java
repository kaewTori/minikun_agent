package com.minikun.pcs;

import java.util.Objects;

public record McsSelectionContext(
        String currentUserMessage,
        String conversationHistory,
        ConversationAttributes conversationAttributes,
    RuntimeAttributes runtimeAttributes,
    MemorySelectionSignals memorySelectionSignals,
    InterestSelectionSignals interestSelectionSignals) {
    public McsSelectionContext(String currentUserMessage, String conversationHistory) {
        this(currentUserMessage, conversationHistory,
        ConversationAttributes.EMPTY, RuntimeAttributes.EMPTY,
        MemorySelectionSignals.EMPTY, InterestSelectionSignals.EMPTY);
    }

    public McsSelectionContext(
        String currentUserMessage,
        String conversationHistory,
        ConversationAttributes conversationAttributes,
        RuntimeAttributes runtimeAttributes) {
    this(currentUserMessage, conversationHistory, conversationAttributes, runtimeAttributes,
        MemorySelectionSignals.EMPTY, InterestSelectionSignals.EMPTY);
    }

    public McsSelectionContext(
        String currentUserMessage,
        String conversationHistory,
        ConversationAttributes conversationAttributes,
        RuntimeAttributes runtimeAttributes,
        MemorySelectionSignals memorySelectionSignals) {
    this(currentUserMessage, conversationHistory, conversationAttributes, runtimeAttributes,
        memorySelectionSignals, InterestSelectionSignals.EMPTY);
    }

    public McsSelectionContext {
        currentUserMessage = Objects.requireNonNullElse(currentUserMessage, "");
        conversationHistory = Objects.requireNonNullElse(conversationHistory, "");
        conversationAttributes = Objects.requireNonNullElse(
                conversationAttributes, ConversationAttributes.EMPTY);
        runtimeAttributes = Objects.requireNonNullElse(runtimeAttributes, RuntimeAttributes.EMPTY);
        memorySelectionSignals = Objects.requireNonNullElse(
            memorySelectionSignals, MemorySelectionSignals.EMPTY);
        interestSelectionSignals = Objects.requireNonNullElse(
            interestSelectionSignals, InterestSelectionSignals.EMPTY);
    }
}
