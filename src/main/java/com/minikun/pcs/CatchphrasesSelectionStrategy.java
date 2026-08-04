package com.minikun.pcs;

public final class CatchphrasesSelectionStrategy extends AbstractLiteralTermSelectionStrategy {
    public static final String NAME = "catchphrases-literal-match";

    public CatchphrasesSelectionStrategy() {
        this(new LiteralTermMatcher());
    }

    public CatchphrasesSelectionStrategy(LiteralTermMatcher matcher) {
        super(matcher);
    }

    @Override
    protected SectionKind supportedSectionKind() {
        return SectionKind.CATCHPHRASES;
    }

    @Override
    protected String unsupportedModuleMessage() {
        return "CatchphrasesSelectionStrategy only supports catchphrases";
    }

    @Override
    protected McsSelectionDecision decisionFor(boolean matched) {
        return matched
                ? McsSelectionDecision.selected(NAME, "Catchphrase matched")
                : McsSelectionDecision.skipped(NAME, "No matching catchphrase");
    }
}