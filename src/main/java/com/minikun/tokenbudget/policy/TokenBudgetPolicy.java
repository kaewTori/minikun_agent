package com.minikun.tokenbudget.policy;

import com.minikun.tokenbudget.domain.TokenBudget;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;

public interface TokenBudgetPolicy {
    TokenBudgetAllocation allocate(TokenBudget budget, long inputTokens);
}
