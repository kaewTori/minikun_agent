package com.minikun.search.internal;

import com.minikun.search.ExpansionRule;
import java.util.List;
import java.util.Objects;

public final class IdentityExpansionRule implements ExpansionRule {
    @Override
    public List<String> expand(String rewrittenQuery) {
        Objects.requireNonNull(rewrittenQuery, "rewritten query must not be null");
        return List.of();
    }
}
