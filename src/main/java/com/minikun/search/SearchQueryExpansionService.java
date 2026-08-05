package com.minikun.search;

import com.minikun.search.model.ExpandedSearchQuery;
import com.minikun.search.model.SearchQuery;

@FunctionalInterface
public interface SearchQueryExpansionService {
    ExpandedSearchQuery expand(SearchQuery query);
}