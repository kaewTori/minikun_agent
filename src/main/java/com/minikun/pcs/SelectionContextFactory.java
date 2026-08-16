package com.minikun.pcs;

import com.minikun.personality.signal.PersonaSelectionSignals;

import java.util.Objects;

public final class SelectionContextFactory {
    private final InterestSelectionSignalProducer signalProducer;

    public SelectionContextFactory() {
        this(new NoOpInterestSelectionSignalProducer());
    }

    public SelectionContextFactory(InterestSelectionSignalProducer signalProducer) {
        this.signalProducer = Objects.requireNonNull(signalProducer, "signalProducer");
    }

    public McsSelectionContext create(String currentUserMessage, String conversationHistory) {
        String normalizedMessage = Objects.requireNonNullElse(currentUserMessage, "");
        String normalizedHistory = Objects.requireNonNullElse(conversationHistory, "");
        return new McsSelectionContext(
                normalizedMessage,
                normalizedHistory,
                ConversationAttributes.EMPTY,
                RuntimeAttributes.EMPTY,
                MemorySelectionSignals.EMPTY,
                signalProducer.produce(normalizedMessage, normalizedHistory), SearchContext.EMPTY,
                PersonaSelectionSignals.EMPTY);
        }

        public McsSelectionContext create(
            String currentUserMessage,
            String conversationHistory,
            SearchSelectionSignals searchSignals) {
        String normalizedMessage = Objects.requireNonNullElse(currentUserMessage, "");
        String normalizedHistory = Objects.requireNonNullElse(conversationHistory, "");
        return new McsSelectionContext(
                normalizedMessage,
                normalizedHistory,
                ConversationAttributes.EMPTY,
                RuntimeAttributes.EMPTY,
                MemorySelectionSignals.EMPTY,
                signalProducer.produce(Objects.requireNonNull(searchSignals, "searchSignals")),
                SearchContext.EMPTY, PersonaSelectionSignals.EMPTY);
    }

            public McsSelectionContext create(
                String currentUserMessage,
                String conversationHistory,
                SearchSelectionSignals searchSignals,
                SearchContext searchContext) {
            String normalizedMessage = Objects.requireNonNullElse(currentUserMessage, "");
            String normalizedHistory = Objects.requireNonNullElse(conversationHistory, "");
            return new McsSelectionContext(
                    normalizedMessage,
                    normalizedHistory,
                    ConversationAttributes.EMPTY,
                    RuntimeAttributes.EMPTY,
                    MemorySelectionSignals.EMPTY,
                    signalProducer.produce(Objects.requireNonNull(searchSignals, "searchSignals")),
                    Objects.requireNonNullElse(searchContext, SearchContext.EMPTY), PersonaSelectionSignals.EMPTY);
            }

    public McsSelectionContext create(String currentUserMessage, String conversationHistory,
            SearchSelectionSignals searchSignals, SearchContext searchContext,
            PersonaSelectionSignals personaSignals) {
        String message = Objects.requireNonNullElse(currentUserMessage, "");
        String history = Objects.requireNonNullElse(conversationHistory, "");
        PersonaSelectionSignals signals = Objects.requireNonNullElse(
                personaSignals, PersonaSelectionSignals.EMPTY);
        MemorySelectionSignals memory = signals.equals(PersonaSelectionSignals.EMPTY)
                ? MemorySelectionSignals.EMPTY
                : new MemorySelectionSignals(signals.memoryAvailable(), signals.recalledMemoryCount(),
                        signals.memoryAvailable());
        return new McsSelectionContext(message, history, ConversationAttributes.EMPTY, RuntimeAttributes.EMPTY,
                memory,
                signalProducer.produce(Objects.requireNonNull(searchSignals, "searchSignals")),
                Objects.requireNonNullElse(searchContext, SearchContext.EMPTY), signals);
    }
}
