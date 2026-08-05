package com.minikun.search;

import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.model.ExpandedSearchQuery;
import com.minikun.search.model.SearchRequest;
import java.util.Objects;

public interface SearchManager {
	KnowledgeContext search(SearchRequest request);

	default KnowledgeContext search(SearchRequest request, ExpandedSearchQuery expandedQuery) {
		Objects.requireNonNull(request, "request must not be null");
		Objects.requireNonNull(expandedQuery, "expanded query must not be null");
		if (expandedQuery.expandedQueries().size() != 1
				|| !expandedQuery.expandedQueries().get(0).equals(request.query())) {
			throw new UnsupportedOperationException("manager does not support expanded queries");
		}
		return search(request);
	}
}