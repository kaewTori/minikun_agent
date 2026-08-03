package com.minikun.diagnostics;

import java.util.Objects;

import com.minikun.pcs.MinikunPersonaProvider.PersonaFragment;

public record DiagnosticsPrompt(
        PersonaFragment persona,
        String instructions,
        DiagnosticsSummary summary,
        String userRequest) {

    public DiagnosticsPrompt {
        Objects.requireNonNull(persona, "persona must not be null");
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("instructions must not be blank");
        }
        Objects.requireNonNull(summary, "summary must not be null");
        userRequest = Objects.requireNonNullElse(userRequest, "");
    }
}