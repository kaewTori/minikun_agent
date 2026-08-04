package com.minikun.pcs;

public record InterestSelectionSignals(boolean interestMatchAvailable) {
    public static final InterestSelectionSignals EMPTY =
            new InterestSelectionSignals(false);
}
