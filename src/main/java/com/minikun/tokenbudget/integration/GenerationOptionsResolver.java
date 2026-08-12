package com.minikun.tokenbudget.integration;

import com.minikun.model.GenerationOptions;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;

public interface GenerationOptionsResolver {
    GenerationOptions resolve(GenerationOptions existing, TokenBudgetAllocation allocation);
}
