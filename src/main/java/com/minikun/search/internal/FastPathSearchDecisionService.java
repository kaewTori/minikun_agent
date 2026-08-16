package com.minikun.search.internal;

import com.minikun.search.SearchDecisionService;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import java.util.Locale;
import java.util.Objects;

/** Avoids a task-model round trip for unambiguous search and non-search requests. */
public final class FastPathSearchDecisionService implements SearchDecisionService {
    private final SearchDecisionService delegate;
    private final SearchDecisionService rules;

    public FastPathSearchDecisionService(SearchDecisionService delegate, SearchDecisionService rules) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.rules = Objects.requireNonNull(rules, "rules must not be null");
    }

    @Override
    public SearchDecision decide(String query) {
        SearchDecision fast = fastDecision(query);
        return fast == null ? delegate.decide(query) : fast;
    }

    @Override
    public SearchDecision decide(String query, String conversationContext) {
        SearchDecision fast = fastDecision(query);
        return fast == null ? delegate.decide(query, conversationContext) : fast;
    }

    private SearchDecision fastDecision(String query) {
        if (query == null || query.isBlank()) {
            return new SearchDecision(false, "", SearchDecisionReason.GENERAL_KNOWLEDGE);
        }
        SearchDecision ruleDecision = rules.decide(query);
        if (ruleDecision.shouldSearch()) {
            return ruleDecision;
        }
        String value = query.trim().toLowerCase(Locale.ROOT);
        if (value.matches("^(อธิบาย|ช่วยอธิบาย|คืออะไร|ทำไม|อย่างไร|แปล|เขียน|สร้าง|สรุป).*\\S.*")
                || value.matches("^(explain|what is|how does|why does|translate|write|create|summarize)\\b.*")) {
            return new SearchDecision(false, query, SearchDecisionReason.GENERAL_KNOWLEDGE);
        }
        return null;
    }
}
