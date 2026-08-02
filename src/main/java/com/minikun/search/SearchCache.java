package com.minikun.search;

import com.minikun.pcs.model.KnowledgeContext;
import java.util.Optional;

public interface SearchCache {
    Optional<KnowledgeContext> get(SearchCacheKey key);

    void put(SearchCacheKey key, KnowledgeContext context);
}