package com.minikun.pcs;

import java.util.Locale;

public final class InterestsSelectionStrategy implements McsSelectionStrategy {
    public static final String NAME = "interests-literal-match";

    @Override
    public McsSelectionDecision select(McsModule module, McsSelectionContext context) {
        if (!module.name().equals("interests")) {
            throw new IllegalArgumentException("InterestsSelectionStrategy only supports interests");
        }
        String text = normalize(context.currentUserMessage()) + " "
                + normalize(context.conversationHistory());
        boolean matched = module.selectionMetadata()
                .map(metadata -> metadata.literalTerms().stream()
                        .map(this::normalize)
                        .filter(term -> !term.isEmpty())
                        .anyMatch(text::contains))
                .orElse(false);
        return matched
                ? McsSelectionDecision.selected(NAME, "Interest matched")
                : McsSelectionDecision.skipped(NAME, "No matching interest");
    }

    private String normalize(String value) {
        String lower = value.trim().toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lower.length());
        boolean whitespace = false;
        for (int index = 0; index < lower.length(); index++) {
            char character = lower.charAt(index);
            if (Character.isWhitespace(character)) {
                whitespace = normalized.length() > 0;
            } else {
                if (whitespace) {
                    normalized.append(' ');
                    whitespace = false;
                }
                normalized.append(character);
            }
        }
        return normalized.toString();
    }
}