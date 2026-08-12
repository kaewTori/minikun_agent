package com.minikun.tokenbudget.diagnostics;

public interface TokenBudgetSafetyPolicy {
    boolean shouldWarn(TokenBudgetDecision decision);
}