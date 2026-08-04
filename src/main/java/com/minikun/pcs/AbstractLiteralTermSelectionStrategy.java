package com.minikun.pcs;

import java.util.Objects;

public abstract class AbstractLiteralTermSelectionStrategy implements McsSelectionStrategy {
    private final LiteralTermMatcher matcher;

    protected AbstractLiteralTermSelectionStrategy(LiteralTermMatcher matcher) {
        this.matcher = Objects.requireNonNull(matcher, "matcher");
    }

    @Override
    public final McsSelectionDecision select(McsModule module, McsSelectionContext context) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(context, "context");
        if (!module.name().equals(supportedSectionKind().moduleName())) {
            throw new IllegalArgumentException(unsupportedModuleMessage());
        }
        return decisionFor(matcher.matches(module.selectionMetadata(), context));
    }

    protected abstract SectionKind supportedSectionKind();

    protected abstract String unsupportedModuleMessage();

    protected abstract McsSelectionDecision decisionFor(boolean matched);
}
