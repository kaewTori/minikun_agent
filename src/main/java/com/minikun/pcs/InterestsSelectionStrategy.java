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
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(context, "context");
        if (!module.name().equals(supportedSectionKind().moduleName())) {
            throw new IllegalArgumentException(unsupportedModuleMessage());
        }
        return decisionFor(matcher.matches(module.selectionMetadata(), context), context);
    }

    protected SectionKind supportedSectionKind() {
        return SectionKind.INTERESTS;
    }

    protected String unsupportedModuleMessage() {
        return "InterestsSelectionStrategy only supports interests";
    }

    protected McsSelectionDecision decisionFor(boolean matched) {
        return matched
                ? McsSelectionDecision.selected(NAME, "Interest matched")
                : McsSelectionDecision.skipped(NAME, "No matching interest");
    }

    private McsSelectionDecision decisionFor(boolean literalMatch, McsSelectionContext context) {
        if (literalMatch) {
            return decisionFor(true);
        }
        if (context.interestSelectionSignals().interestMatchAvailable()) {
            return McsSelectionDecision.selected(NAME, "Interest selection signal matched");
        }
        return decisionFor(false);
    }
}