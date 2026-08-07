package com.minikun.search;

import com.minikun.pcs.SearchSelectionSignals;
import com.minikun.search.model.SearchDecision;
import java.util.Objects;

public final class SearchSelectionSignalMapper {
    public SearchSelectionSignals map(SearchDecision decision) {
        Objects.requireNonNull(decision, "decision");
        return decision.shouldSearch()
                ? new SearchSelectionSignals(true)
                : SearchSelectionSignals.EMPTY;
    }
}
