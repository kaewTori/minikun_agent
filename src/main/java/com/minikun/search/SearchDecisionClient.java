package com.minikun.search;

import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionPrompt;

public interface SearchDecisionClient {
    SearchDecision classify(SearchDecisionPrompt prompt);
}