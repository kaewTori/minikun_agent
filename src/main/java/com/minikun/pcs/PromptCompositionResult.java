package com.minikun.pcs;

import com.minikun.pcs.model.Prompt;

import java.util.List;
import java.util.Objects;

public record PromptCompositionResult(Prompt prompt, List<McsSelectionDecision> selectionDecisions) {
    public PromptCompositionResult {
        prompt = Objects.requireNonNull(prompt, "prompt");
        selectionDecisions = List.copyOf(
                Objects.requireNonNull(selectionDecisions, "selectionDecisions"));
    }

    public List<McsSelectionDiagnostic> selectionDiagnostics() {
        return selectionDecisions.stream().map(McsSelectionDiagnostic::from).toList();
    }
}
