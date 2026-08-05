package com.minikun.search.internal;

import com.minikun.search.ExpansionRule;
import com.minikun.search.SearchQueryExpansionService;
import com.minikun.search.model.ExpandedSearchQuery;
import com.minikun.search.model.SearchQuery;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class RuleBasedSearchQueryExpansionService implements SearchQueryExpansionService {
    private final List<ExpansionRule> rules;

    public RuleBasedSearchQueryExpansionService(List<? extends ExpansionRule> rules) {
        Objects.requireNonNull(rules, "rules must not be null");
        this.rules = List.copyOf(rules);
    }

    @Override
    public ExpandedSearchQuery expand(SearchQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        String rewrittenQuery = query.rewrittenQuery();
        LinkedHashSet<String> expandedQueries = new LinkedHashSet<>();
        expandedQueries.add(rewrittenQuery);
        for (ExpansionRule rule : rules) {
            List<String> ruleQueries = Objects.requireNonNull(
                    rule.expand(rewrittenQuery), "rule result must not be null");
            for (String ruleQuery : ruleQueries) {
                expandedQueries.add(Objects.requireNonNull(
                        ruleQuery, "expanded query must not be null"));
            }
        }
        return new ExpandedSearchQuery(
                new String(query.originalQuery()),
                new String(rewrittenQuery),
                new ArrayList<>(expandedQueries));
    }
}
