package com.minikun.search.internal;

import com.minikun.search.SearchQueryExpansionService;
import com.minikun.search.model.ExpandedSearchQuery;
import com.minikun.search.model.SearchQuery;
import java.util.List;
import java.util.Objects;

public final class DefaultSearchQueryExpansionService implements SearchQueryExpansionService {
    private final RuleBasedSearchQueryExpansionService delegate =
            new RuleBasedSearchQueryExpansionService(List.of(new IdentityExpansionRule()));

    @Override
    public ExpandedSearchQuery expand(SearchQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return delegate.expand(query);
    }
}