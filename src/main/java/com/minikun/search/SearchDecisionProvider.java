package com.minikun.search;

import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionPrompt;

public interface SearchDecisionProvider {
    SearchDecision classify(SearchDecisionPrompt prompt);
}