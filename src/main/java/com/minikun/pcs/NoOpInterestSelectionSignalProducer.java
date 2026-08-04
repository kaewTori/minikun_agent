package com.minikun.pcs;

public final class NoOpInterestSelectionSignalProducer implements InterestSelectionSignalProducer {
    @Override
    public InterestSelectionSignals produce(String currentMessage, String conversationHistory) {
        return InterestSelectionSignals.EMPTY;
    }
}
