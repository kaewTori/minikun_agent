package com.minikun.pcs;

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
                signalProducer.produce(normalizedMessage, normalizedHistory));
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
                signalProducer.produce(Objects.requireNonNull(searchSignals, "searchSignals")));
    }
}
