package com.minikun.pcs;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class LiteralTermMatcher {
    public boolean matches(Optional<McsSelectionMetadata> metadata, McsSelectionContext context) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(context, "context");

        String text = normalize(context.currentUserMessage()) + " "
                + normalize(context.conversationHistory());
        return metadata.map(value -> value.literalTerms().stream()
                .map(this::normalize)
                .filter(term -> !term.isEmpty())
                .anyMatch(text::contains))
                .orElse(false);
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