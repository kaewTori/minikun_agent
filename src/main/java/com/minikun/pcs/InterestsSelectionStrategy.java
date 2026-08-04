package com.minikun.pcs;

public final class InterestsSelectionStrategy extends AbstractLiteralTermSelectionStrategy {
    public static final String NAME = "interests-literal-match";

    public InterestsSelectionStrategy() {
        this(new LiteralTermMatcher());
    }

    public InterestsSelectionStrategy(LiteralTermMatcher matcher) {
        super(matcher);
    }

    @Override
    protected SectionKind supportedSectionKind() {
        return SectionKind.INTERESTS;
    }

    @Override
    protected String unsupportedModuleMessage() {
        return "InterestsSelectionStrategy only supports interests";
    }

    @Override
    protected McsSelectionDecision decisionFor(boolean matched) {
        return matched
                ? McsSelectionDecision.selected(NAME, "Interest matched")
                : McsSelectionDecision.skipped(NAME, "No matching interest");
    }
}