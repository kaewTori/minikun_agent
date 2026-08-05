package com.minikun.search.internal;

import com.minikun.search.SearchQueryExpansionService;
import com.minikun.search.model.ExpandedSearchQuery;
import com.minikun.search.model.SearchQuery;
import java.util.List;
import java.util.Objects;

public final class DefaultSearchQueryExpansionService implements SearchQueryExpansionService {
    @Override
    public ExpandedSearchQuery expand(SearchQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return new ExpandedSearchQuery(
                new String(query.originalQuery()),
                new String(query.rewrittenQuery()),
                List.of(new String(query.rewrittenQuery())));
    }
}