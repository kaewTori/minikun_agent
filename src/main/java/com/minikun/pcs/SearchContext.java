package com.minikun.pcs;

import com.minikun.search.model.SearchDecisionReason;
import java.util.Objects;

public record SearchContext(
        String userQuery,
        boolean conversationContextAvailable,
        boolean memoryKnowledgeAvailable,
        boolean searchDecisionAvailable,
        boolean searchRequested,
        boolean searchAttempted,
        boolean searchKnowledgeAvailable,
        SearchDecisionReason searchDecisionReason) {
    public static final SearchContext EMPTY = new SearchContext(
            "", false, false, false, false, false, false,
            SearchDecisionReason.GENERAL_KNOWLEDGE);

    public SearchContext {
        Objects.requireNonNull(userQuery, "user query must not be null");
        Objects.requireNonNull(searchDecisionReason, "search decision reason must not be null");
    }
}
