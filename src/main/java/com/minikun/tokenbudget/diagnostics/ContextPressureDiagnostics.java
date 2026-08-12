package com.minikun.tokenbudget.diagnostics;

import com.minikun.tokenbudget.pressure.ContextPressureDecision;
import com.minikun.tokenbudget.pressure.RecoveryAction;
import com.minikun.tokenbudget.recovery.ContextRecoveryPlan;
import com.minikun.tokenbudget.recovery.RecoveryExecutionResult;
import com.minikun.tokenbudget.recovery.RecoveryPriorityPlan;

import java.util.Objects;

public record ContextPressureDiagnostics(
        ContextPressureDecision decision,
        RecoveryAction recoveryAction,
        ContextRecoveryPlan recoveryPlan,
        RecoveryPriorityPlan priorityPlan,
        RecoveryExecutionResult executionResult) {
    public ContextPressureDiagnostics(
            ContextPressureDecision decision,
            RecoveryAction recoveryAction) {
        this(decision, recoveryAction, null, null, null);
    }

    public ContextPressureDiagnostics(ContextPressureDecision decision) {
        this(decision, null, null, null, null);
    }

    public ContextPressureDiagnostics(
            ContextPressureDecision decision,
            RecoveryAction recoveryAction,
            ContextRecoveryPlan recoveryPlan) {
        this(decision, recoveryAction, recoveryPlan, null, null);
    }

    public ContextPressureDiagnostics(
            ContextPressureDecision decision,
            RecoveryAction recoveryAction,
            ContextRecoveryPlan recoveryPlan,
            RecoveryPriorityPlan priorityPlan) {
        this(decision, recoveryAction, recoveryPlan, priorityPlan, null);
    }

    public ContextPressureDiagnostics {
        Objects.requireNonNull(decision, "context pressure decision must not be null");
        if (recoveryAction == null && recoveryPlan != null) {
            throw new IllegalArgumentException(
                    "recovery action must be present when recovery plan is present");
        }
    }
}