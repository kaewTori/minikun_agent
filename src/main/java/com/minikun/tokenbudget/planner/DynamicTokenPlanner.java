package com.minikun.tokenbudget.planner;

import com.minikun.model.capability.ModelCapability;
import com.minikun.tokenbudget.domain.TokenBudget;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;

public interface DynamicTokenPlanner {
    TokenBudgetAllocation plan(ModelCapability capability, TokenBudget budget, String prompt);
}
