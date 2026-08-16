package com.minikun.pcs;

import com.minikun.personality.signal.PersonaSelectionSignals;

import java.util.Objects;

public record McsSelectionContext(
        String currentUserMessage,
        String conversationHistory,
        ConversationAttributes conversationAttributes,
    RuntimeAttributes runtimeAttributes,
    MemorySelectionSignals memorySelectionSignals,
    InterestSelectionSignals interestSelectionSignals,
    SearchContext searchContext,
    PersonaSelectionSignals personaSelectionSignals) {
    public McsSelectionContext(String currentUserMessage, String conversationHistory) {
        this(currentUserMessage, conversationHistory,
        ConversationAttributes.EMPTY, RuntimeAttributes.EMPTY,
        MemorySelectionSignals.EMPTY, InterestSelectionSignals.EMPTY, SearchContext.EMPTY,
        PersonaSelectionSignals.EMPTY);
    }

    public McsSelectionContext(
        String currentUserMessage,
        String conversationHistory,
        ConversationAttributes conversationAttributes,
        RuntimeAttributes runtimeAttributes) {
    this(currentUserMessage, conversationHistory, conversationAttributes, runtimeAttributes,
    MemorySelectionSignals.EMPTY, InterestSelectionSignals.EMPTY, SearchContext.EMPTY,
        PersonaSelectionSignals.EMPTY);
    }

    public McsSelectionContext(
        String currentUserMessage,
        String conversationHistory,
        ConversationAttributes conversationAttributes,
        RuntimeAttributes runtimeAttributes,
        MemorySelectionSignals memorySelectionSignals) {
    this(currentUserMessage, conversationHistory, conversationAttributes, runtimeAttributes,
        memorySelectionSignals, InterestSelectionSignals.EMPTY, SearchContext.EMPTY,
        PersonaSelectionSignals.EMPTY);
    }

    public McsSelectionContext(
        String currentUserMessage,
        String conversationHistory,
        ConversationAttributes conversationAttributes,
        RuntimeAttributes runtimeAttributes,
        MemorySelectionSignals memorySelectionSignals,
        InterestSelectionSignals interestSelectionSignals) {
    this(currentUserMessage, conversationHistory, conversationAttributes, runtimeAttributes,
        memorySelectionSignals, interestSelectionSignals, SearchContext.EMPTY,
        PersonaSelectionSignals.EMPTY);
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
        searchContext = Objects.requireNonNullElse(searchContext, SearchContext.EMPTY);
        personaSelectionSignals = Objects.requireNonNullElse(
                personaSelectionSignals, PersonaSelectionSignals.EMPTY);
    }
}
