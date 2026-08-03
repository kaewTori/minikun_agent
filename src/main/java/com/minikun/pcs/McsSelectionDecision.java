package com.minikun.pcs;

import com.minikun.character.model.LoadingPolicy;

import java.util.Objects;

public record McsSelectionDecision(
        McsModule module,
        LoadingPolicy loadingPolicy,
        boolean selected,
        String selector,
        String reason) {
    public McsSelectionDecision {
        if (module != null && loadingPolicy == null) {
            throw new IllegalArgumentException("loadingPolicy must not be null for a module decision");
        }
        selector = requireText(selector, "selector");
        reason = requireText(reason, "reason");
    }

    public McsSelectionDecision(boolean selected, String selector, String reason) {
        this(null, null, selected, selector, reason);
    }

    public static McsSelectionDecision selected(String selector, String reason) {
        return new McsSelectionDecision(true, selector, reason);
    }

    public static McsSelectionDecision skipped(String selector, String reason) {
        return new McsSelectionDecision(false, selector, reason);
    }

    McsSelectionDecision forModule(McsModule module) {
        return new McsSelectionDecision(module, module.loadingPolicy(), selected, selector, reason);
    }

    private static String requireText(String value, String field) {
        if (Objects.isNull(value) || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
