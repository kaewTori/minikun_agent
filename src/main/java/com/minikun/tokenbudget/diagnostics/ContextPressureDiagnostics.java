package com.minikun.tokenbudget.diagnostics;

import com.minikun.tokenbudget.pressure.ContextPressureDecision;
import com.minikun.tokenbudget.pressure.RecoveryAction;

import java.util.Objects;

public record ContextPressureDiagnostics(
        ContextPressureDecision decision,
        RecoveryAction recoveryAction) {
    public ContextPressureDiagnostics {
        Objects.requireNonNull(decision, "context pressure decision must not be null");
        Objects.requireNonNull(recoveryAction, "recovery action must not be null");
    }
}