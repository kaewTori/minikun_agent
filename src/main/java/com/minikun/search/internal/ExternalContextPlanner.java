package com.minikun.search.internal;

import com.minikun.search.model.ExternalContextAction;
import com.minikun.search.model.ExternalContextDecision;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;

/** Converts independent search/image/url signals into one auditable external-context action. */
public final class ExternalContextPlanner {
    public ExternalContextDecision plan(
            SearchDecision searchDecision,
            boolean explicitUrl,
            boolean conversationContextAvailable,
            boolean memoryAvailable) {
        if (searchDecision == null) {
            throw new IllegalArgumentException("search decision must not be null");
        }
        ExternalContextAction action;
        double confidence;
        String reason;
        if (searchDecision.reason() == SearchDecisionReason.IMAGE_REQUEST) {
            action = ExternalContextAction.IMAGE_SEARCH;
            confidence = 0.95;
            reason = "image intent";
        } else if (explicitUrl && searchDecision.shouldSearch()) {
            action = ExternalContextAction.SEARCH_THEN_OPEN;
            confidence = 0.90;
            reason = "explicit URL plus web search intent";
        } else if (explicitUrl) {
            action = ExternalContextAction.OPEN_EXPLICIT_URL;
            confidence = 0.98;
            reason = "explicit URL supplied by user";
        } else if (searchDecision.shouldSearch()) {
            action = ExternalContextAction.SEARCH_WEB;
            confidence = 0.85;
            reason = "classifier requested web search";
        } else if (memoryAvailable || conversationContextAvailable) {
            action = ExternalContextAction.MEMORY_ONLY;
            confidence = 0.75;
            reason = "no external search required; local context is available";
        } else {
            action = ExternalContextAction.NO_EXTERNAL_CONTEXT;
            confidence = 0.70;
            reason = "general knowledge request";
        }
        return new ExternalContextDecision(action, searchDecision, explicitUrl,
                conversationContextAvailable, confidence, reason);
    }
}
