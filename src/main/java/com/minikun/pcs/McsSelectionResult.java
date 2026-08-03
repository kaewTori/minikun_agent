package com.minikun.pcs;

import java.util.List;
import java.util.Objects;

public record McsSelectionResult(
        List<McsModule> selectedModules,
        List<McsSelectionDiagnostic> diagnostics) {
    public McsSelectionResult {
        selectedModules = List.copyOf(Objects.requireNonNull(selectedModules, "selectedModules"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }
}
