package com.minikun.tokenbudget.pressure;

import com.minikun.model.capability.ModelCapability;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;

public interface ContextPressureAnalyzer {
    ContextPressureDecision analyze(
            ModelCapability capability,
            TokenBudgetAllocation allocation);
}