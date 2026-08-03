package com.minikun.pcs;

import java.util.Locale;

public final class ModuleNameMentionStrategy implements McsSelectionStrategy {
    public static final String NAME = "module-name-mention";

    @Override
    public McsSelectionDecision select(McsModule module, McsSelectionContext context) {
        String moduleName = module.name().toLowerCase(Locale.ROOT);
        String text = (context.currentUserMessage() + "\n" + context.conversationHistory())
                .toLowerCase(Locale.ROOT);
        boolean matched = text.matches(".*\\b" + moduleName + "\\b.*");
        return matched
                ? McsSelectionDecision.selected(NAME, "Strategy matched")
                : McsSelectionDecision.skipped(NAME, "Strategy not matched");
    }
}
