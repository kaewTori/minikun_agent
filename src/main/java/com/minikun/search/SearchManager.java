package com.minikun.search;

import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.model.SearchRequest;

public interface SearchManager {
	KnowledgeContext search(SearchRequest request);
}