package com.minikun.tokenbudget.recovery;

import com.minikun.tokenbudget.pressure.ContextPressureDecision;

public interface ContextRecoveryPlanner {
    ContextRecoveryPlan plan(ContextPressureDecision decision);
}