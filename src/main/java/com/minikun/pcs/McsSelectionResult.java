package com.minikun.pcs;

import java.util.List;
import java.util.Objects;

public final class McsSelectionResult {
    private final List<McsModule> selectedModules;
    private final List<McsSelectionDecision> decisions;

    McsSelectionResult(List<McsSelectionDecision> decisions) {
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
        if (decisions.stream().anyMatch(decision -> decision.module() == null)) {
            throw new IllegalArgumentException("selection decisions must identify their modules");
        }
        if (decisions.stream().map(decision -> decision.module().name()).distinct().count() != decisions.size()) {
            throw new IllegalArgumentException("selection decisions must identify each module exactly once");
        }
        this.decisions = decisions;
        this.selectedModules = decisions.stream()
                .filter(McsSelectionDecision::selected)
                .map(McsSelectionDecision::module)
                .toList();
    }

    public List<McsModule> selectedModules() {
        return selectedModules;
    }

    public List<McsSelectionDecision> decisions() {
        return decisions;
    }

    public List<McsSelectionDiagnostic> diagnostics() {
        return decisions.stream().map(McsSelectionDiagnostic::from).toList();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof McsSelectionResult result)) {
            return false;
        }
        return selectedModules.equals(result.selectedModules) && decisions.equals(result.decisions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(selectedModules, decisions);
    }
}
