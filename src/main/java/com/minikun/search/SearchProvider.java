package com.minikun.search;

import com.minikun.search.model.SearchProviderResponse;
import com.minikun.search.model.SearchRequest;

public interface SearchProvider {
    SearchProviderResponse search(SearchRequest request);
}