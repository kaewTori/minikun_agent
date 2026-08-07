package com.minikun.search.internal;

import com.minikun.search.AcronymDictionary;
import com.minikun.search.ExpansionRule;
import java.util.List;
import java.util.Objects;

public final class AcronymExpansionRule implements ExpansionRule {
    private final AcronymDictionary dictionary;

    public AcronymExpansionRule(AcronymDictionary dictionary) {
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary must not be null");
    }

    @Override
    public List<String> expand(String rewrittenQuery) {
        Objects.requireNonNull(rewrittenQuery, "rewritten query must not be null");
        return dictionary.expansionsOf(rewrittenQuery);
    }
}
