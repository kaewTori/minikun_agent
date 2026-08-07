package com.minikun.pcs;

import java.util.Objects;

public final class SearchInterestSelectionSignalProducer
        implements InterestSelectionSignalProducer {
    @Override
    public InterestSelectionSignals produce(String currentMessage, String conversationHistory) {
        return InterestSelectionSignals.EMPTY;
    }

    @Override
    public InterestSelectionSignals produce(SearchSelectionSignals signals) {
        Objects.requireNonNull(signals, "signals");
        return signals.searchRequested()
                ? new InterestSelectionSignals(true)
                : InterestSelectionSignals.EMPTY;
    }
}
