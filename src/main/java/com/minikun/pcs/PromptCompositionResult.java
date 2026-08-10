package com.minikun.pcs;

import com.minikun.pcs.model.Prompt;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record PromptCompositionResult(
        Prompt prompt,
        List<McsSelectionDecision> selectionDecisions,
        Optional<ContextProcessingResult> contextProcessingResult) {
    public PromptCompositionResult(Prompt prompt, List<McsSelectionDecision> selectionDecisions) {
        this(prompt, selectionDecisions, Optional.empty());
    }

    public PromptCompositionResult {
        prompt = Objects.requireNonNull(prompt, "prompt");
        selectionDecisions = List.copyOf(
                Objects.requireNonNull(selectionDecisions, "selectionDecisions"));
        contextProcessingResult = Objects.requireNonNull(
                contextProcessingResult, "context processing result must not be null");
    }

    public List<McsSelectionDiagnostic> selectionDiagnostics() {
        return selectionDecisions.stream().map(McsSelectionDiagnostic::from).toList();
    }
}
