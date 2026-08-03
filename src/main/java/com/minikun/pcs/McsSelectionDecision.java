package com.minikun.pcs;

import java.util.Objects;

public record McsSelectionDecision(boolean selected, String selector, String reason) {
    public McsSelectionDecision {
        selector = requireText(selector, "selector");
        reason = requireText(reason, "reason");
    }

    public static McsSelectionDecision selected(String selector, String reason) {
        return new McsSelectionDecision(true, selector, reason);
    }

    public static McsSelectionDecision skipped(String selector, String reason) {
        return new McsSelectionDecision(false, selector, reason);
    }

    private static String requireText(String value, String field) {
        if (Objects.isNull(value) || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
