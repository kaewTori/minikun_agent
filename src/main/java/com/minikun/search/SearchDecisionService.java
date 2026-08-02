package com.minikun.search;

import com.minikun.search.model.SearchDecision;

public interface SearchDecisionService {
    SearchDecision decide(String query);
}