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

    static McsSelectionDiagnostic from(McsSelectionDecision decision) {
        Objects.requireNonNull(decision, "decision");
        McsModule module = Objects.requireNonNull(decision.module(), "decision.module");
        return new McsSelectionDiagnostic(module.name(), module.loadingPolicy(), decision.selected(),
                decision.selector(), decision.reason());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
