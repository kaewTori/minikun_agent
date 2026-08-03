package com.minikun.pcs;

import com.minikun.character.model.LoadingPolicy;

import java.util.Objects;

public record McsSelectionDiagnostic(
        String module,
        LoadingPolicy loadingPolicy,
        boolean selected,
        String selector,
        String reason) {
    public McsSelectionDiagnostic {
        module = requireText(module, "module");
        loadingPolicy = Objects.requireNonNull(loadingPolicy, "loadingPolicy");
        selector = requireText(selector, "selector");
        reason = requireText(reason, "reason");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
