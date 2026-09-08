package com.minikun.search.internal;

import com.minikun.search.SearchDecisionService;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import java.util.Objects;

public final class ImageIntentSearchDecisionService implements SearchDecisionService {
    private final SearchDecisionService delegate;
    private final ImageIntentDetector detector;

    public ImageIntentSearchDecisionService(
            SearchDecisionService delegate, ImageIntentDetector detector) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.detector = Objects.requireNonNull(detector, "detector must not be null");
    }

    @Override
    public SearchDecision decide(String query) {
        SearchDecision decision = delegate.decide(query);
        return applyImageIntent(query, null, decision);
    }

    @Override
    public SearchDecision decide(String query, String conversationContext) {
        SearchDecision decision = delegate.decide(query, conversationContext);
        return applyImageIntent(query, conversationContext, decision);
    }

    private SearchDecision applyImageIntent(
            String query, String conversationContext, SearchDecision decision) {
        // ponytail: keep the lexical fallback small; expand the semantic router, not this list.
        if (!detector.detects(query, conversationContext)
                || decision.reason() == SearchDecisionReason.IMAGE_REQUEST) {
            return decision;
        }
        return new SearchDecision(true, decision.query(), SearchDecisionReason.IMAGE_REQUEST);
    }
}
