package com.minikun.pcs;

import java.util.Objects;

public final class CatchphrasesSelectionStrategy implements McsSelectionStrategy {
    public static final String NAME = "catchphrases-literal-match";
    private final LiteralTermMatcher matcher;

    public CatchphrasesSelectionStrategy() {
        this(new LiteralTermMatcher());
    }

    public CatchphrasesSelectionStrategy(LiteralTermMatcher matcher) {
        this.matcher = Objects.requireNonNull(matcher, "matcher");
    }

    @Override
    public McsSelectionDecision select(McsModule module, McsSelectionContext context) {
        if (!module.name().equals("catchphrases")) {
            throw new IllegalArgumentException("CatchphrasesSelectionStrategy only supports catchphrases");
        }
        boolean matched = matcher.matches(module.selectionMetadata(), context);
        return matched
                ? McsSelectionDecision.selected(NAME, "Catchphrase matched")
                : McsSelectionDecision.skipped(NAME, "No matching catchphrase");
    }
}