package com.minikun.pcs;

public record SearchSelectionSignals(boolean searchRequested) {
    public static final SearchSelectionSignals EMPTY = new SearchSelectionSignals(false);
}
