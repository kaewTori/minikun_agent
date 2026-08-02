package com.minikun.search;

import com.minikun.search.model.SearchRequest;
import com.minikun.search.model.SearchResponse;

public interface SearchService {
    SearchResponse search(SearchRequest request);
}