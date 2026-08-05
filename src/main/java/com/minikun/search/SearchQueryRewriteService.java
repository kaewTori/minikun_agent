package com.minikun.search;

import com.minikun.search.model.SearchQuery;

@FunctionalInterface
public interface SearchQueryRewriteService {
    SearchQuery rewrite(String query);
}