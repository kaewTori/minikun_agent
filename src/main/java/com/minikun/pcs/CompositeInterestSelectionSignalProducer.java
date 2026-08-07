package com.minikun.pcs;

import java.util.List;
import java.util.Objects;

public final class CompositeInterestSelectionSignalProducer
        implements InterestSelectionSignalProducer {
    private final List<InterestSelectionSignalProducer> producers;

    public CompositeInterestSelectionSignalProducer(
            List<InterestSelectionSignalProducer> producers) {
        List<InterestSelectionSignalProducer> copiedProducers =
                List.copyOf(Objects.requireNonNull(producers, "producers"));
        if (copiedProducers.size() != 1) {
            throw new IllegalArgumentException("exactly one producer is required");
        }
        this.producers = copiedProducers;
    }

    @Override
    public InterestSelectionSignals produce(String currentMessage, String conversationHistory) {
        return producers.get(0).produce(currentMessage, conversationHistory);
    }

    @Override
    public InterestSelectionSignals produce(SearchSelectionSignals signals) {
        return producers.get(0).produce(signals);
    }
}
