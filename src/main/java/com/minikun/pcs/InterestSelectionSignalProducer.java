package com.minikun.pcs;

@FunctionalInterface
public interface InterestSelectionSignalProducer {
    InterestSelectionSignals produce(String currentMessage, String conversationHistory);
}
