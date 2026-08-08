package com.minikun.search;

import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.pcs.SearchContext;
import com.minikun.search.model.SearchDecision;

public interface SearchContextAwarenessService {
    SearchContext observe(
            String userQuery,
            boolean conversationContextAvailable,
            KnowledgeContext memoryKnowledge,
            SearchDecision searchDecision,
            boolean searchAttempted,
            KnowledgeContext searchKnowledge);
}
