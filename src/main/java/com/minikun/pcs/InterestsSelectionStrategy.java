package com.minikun.pcs;

import java.util.Objects;

public final class InterestsSelectionStrategy implements McsSelectionStrategy {
    public static final String NAME = "interests-literal-match";
    private final LiteralTermMatcher matcher;

    public InterestsSelectionStrategy() {
        this(new LiteralTermMatcher());
    }

    public InterestsSelectionStrategy(LiteralTermMatcher matcher) {
        this.matcher = Objects.requireNonNull(matcher, "matcher");
    }

    @Override
    public McsSelectionDecision select(McsModule module, McsSelectionContext context) {
        if (!module.name().equals("interests")) {
            throw new IllegalArgumentException("InterestsSelectionStrategy only supports interests");
        }
        boolean matched = matcher.matches(module.selectionMetadata(), context);
        return matched
                ? McsSelectionDecision.selected(NAME, "Interest matched")
                : McsSelectionDecision.skipped(NAME, "No matching interest");
    }
}