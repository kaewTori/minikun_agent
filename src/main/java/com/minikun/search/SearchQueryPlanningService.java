package com.minikun.search;

import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchQueryPlan;

public interface SearchQueryPlanningService {
    SearchQueryPlan plan(String query, SearchDecision decision);
}
