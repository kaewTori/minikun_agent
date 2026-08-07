package com.minikun.search.internal;

import com.minikun.search.AliasDictionary;
import com.minikun.search.ExpansionRule;
import java.util.List;
import java.util.Objects;

public final class AliasExpansionRule implements ExpansionRule {
    private final AliasDictionary dictionary;

    public AliasExpansionRule(AliasDictionary dictionary) {
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary must not be null");
    }

    @Override
    public List<String> expand(String rewrittenQuery) {
        Objects.requireNonNull(rewrittenQuery, "rewritten query must not be null");
        return dictionary.aliasesOf(rewrittenQuery);
    }
}
