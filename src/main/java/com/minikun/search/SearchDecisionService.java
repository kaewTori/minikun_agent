package com.minikun.search;

import com.minikun.search.model.SearchDecision;

public interface SearchDecisionService {
    SearchDecision decide(String query);

    /** Context-aware hook; simple/rule implementations may ignore the context. */
    default SearchDecision decide(String query, String conversationContext) {
        return decide(query);
    }
}
