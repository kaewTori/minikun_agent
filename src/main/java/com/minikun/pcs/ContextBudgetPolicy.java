package com.minikun.pcs;

public interface ContextBudgetPolicy {
    ContextBudget allocate(long totalBudget);
}
