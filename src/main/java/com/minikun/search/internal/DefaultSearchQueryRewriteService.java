package com.minikun.search.internal;

import com.minikun.search.SearchQueryRewriteService;
import com.minikun.search.model.SearchQuery;
import java.util.Objects;

public final class DefaultSearchQueryRewriteService implements SearchQueryRewriteService {
    @Override
    public SearchQuery rewrite(String query) {
        Objects.requireNonNull(query, "query must not be null");
        return new SearchQuery(new String(query), new String(query));
    }
}