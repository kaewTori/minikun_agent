package com.minikun.search.internal;

import com.minikun.search.ExpansionRule;
import com.minikun.search.SynonymDictionary;
import java.util.List;
import java.util.Objects;

public final class SynonymExpansionRule implements ExpansionRule {
    private final SynonymDictionary dictionary;

    public SynonymExpansionRule(SynonymDictionary dictionary) {
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary must not be null");
    }

    @Override
    public List<String> expand(String rewrittenQuery) {
        Objects.requireNonNull(rewrittenQuery, "rewritten query must not be null");
        return dictionary.synonymsOf(rewrittenQuery);
    }
}