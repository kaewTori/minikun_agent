package com.minikun.pcs;

@FunctionalInterface
public interface InterestSelectionSignalProducer {
    InterestSelectionSignals produce(String currentMessage, String conversationHistory);

    default InterestSelectionSignals produce(SearchSelectionSignals signals) {
        return produce(null, null);
    }
}
