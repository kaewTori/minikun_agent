package com.minikun.search.internal;

import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.pcs.SearchContext;
import com.minikun.search.SearchContextAwarenessService;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import java.util.Objects;

public final class DefaultSearchContextAwarenessService implements SearchContextAwarenessService {
    @Override
    public SearchContext observe(
            String userQuery,
            boolean conversationContextAvailable,
            KnowledgeContext memoryKnowledge,
            SearchDecision searchDecision,
            boolean searchAttempted,
            KnowledgeContext searchKnowledge) {
        Objects.requireNonNull(userQuery, "user query must not be null");
        return new SearchContext(
                userQuery,
                conversationContextAvailable,
                hasKnowledge(memoryKnowledge),
                searchDecision != null,
                searchDecision != null && searchDecision.shouldSearch(),
                searchAttempted,
                hasKnowledge(searchKnowledge),
                searchDecision == null
                        ? SearchDecisionReason.GENERAL_KNOWLEDGE
                        : searchDecision.reason());
    }

    private boolean hasKnowledge(KnowledgeContext knowledge) {
        return knowledge != null && !knowledge.content().isBlank();
    }
}
