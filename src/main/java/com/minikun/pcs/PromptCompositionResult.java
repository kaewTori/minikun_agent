package com.minikun.pcs;

import com.minikun.pcs.model.Prompt;

import java.util.List;
import java.util.Objects;

public record PromptCompositionResult(Prompt prompt, List<McsSelectionDiagnostic> selectionDiagnostics) {
    public PromptCompositionResult {
        prompt = Objects.requireNonNull(prompt, "prompt");
        selectionDiagnostics = List.copyOf(
                Objects.requireNonNull(selectionDiagnostics, "selectionDiagnostics"));
    }
}
