package com.minikun.tokenbudget.runtime;

import com.minikun.model.ChatModelId;
import com.minikun.model.capability.DefaultModelCapabilityRegistry;
import com.minikun.model.capability.ModelCapability;
import com.minikun.model.capability.ModelCapabilityRegistry;
import com.minikun.model.capability.ModelRole;
import com.minikun.tokenbudget.counter.ApproximateTokenCounter;
import com.minikun.tokenbudget.counter.TokenCounter;
import com.minikun.tokenbudget.diagnostics.DefaultTokenBudgetSafetyPolicy;
import com.minikun.tokenbudget.diagnostics.TokenBudgetDecisionMapper;
import com.minikun.tokenbudget.diagnostics.TokenBudgetSafetyPolicy;
import com.minikun.tokenbudget.integration.DefaultGenerationOptionsResolver;
import com.minikun.tokenbudget.integration.GenerationOptionsResolver;
import com.minikun.tokenbudget.planner.DefaultDynamicTokenPlanner;
import com.minikun.tokenbudget.planner.DynamicTokenPlanner;
import com.minikun.tokenbudget.policy.DefaultTokenBudgetPolicy;
import com.minikun.tokenbudget.policy.TokenBudgetPolicy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class TokenBudgetRuntimeConfiguration {
    private static final long DEFAULT_CONTEXT_WINDOW_TOKENS = 16_384L;
    private static final long DEFAULT_MAX_OUTPUT_TOKENS = 4_096L;

    @Bean
    TokenCounter tokenCounter() {
        return new ApproximateTokenCounter();
    }

    @Bean
    TokenBudgetPolicy tokenBudgetPolicy() {
        return new DefaultTokenBudgetPolicy();
    }

    @Bean
    DynamicTokenPlanner dynamicTokenPlanner(TokenCounter tokenCounter, TokenBudgetPolicy tokenBudgetPolicy) {
        return new DefaultDynamicTokenPlanner(tokenCounter, tokenBudgetPolicy);
    }

    @Bean
    GenerationOptionsResolver generationOptionsResolver() {
        return new DefaultGenerationOptionsResolver();
    }

    @Bean
    TokenBudgetDecisionMapper tokenBudgetDecisionMapper() {
        return new TokenBudgetDecisionMapper();
    }

    @Bean
    TokenBudgetSafetyPolicy tokenBudgetSafetyPolicy() {
        return new DefaultTokenBudgetSafetyPolicy();
    }

    @Bean
    DynamicGenerationOptionsFactory dynamicGenerationOptionsFactory(
            DynamicTokenPlanner dynamicTokenPlanner,
            GenerationOptionsResolver generationOptionsResolver,
            TokenBudgetDecisionMapper tokenBudgetDecisionMapper,
            TokenBudgetSafetyPolicy tokenBudgetSafetyPolicy) {
        return new DynamicGenerationOptionsFactory(dynamicTokenPlanner, generationOptionsResolver,
                tokenBudgetDecisionMapper, tokenBudgetSafetyPolicy);
    }

    @Bean
    ModelCapabilityRegistry modelCapabilityRegistry() {
        return new DefaultModelCapabilityRegistry(Map.of(
                ChatModelId.EXISTING,
                new ModelCapability(ChatModelId.EXISTING, ModelRole.CHAT,
                        DEFAULT_CONTEXT_WINDOW_TOKENS, DEFAULT_MAX_OUTPUT_TOKENS),
                ChatModelId.TINYGRAD,
                new ModelCapability(ChatModelId.TINYGRAD, ModelRole.CHAT,
                        DEFAULT_CONTEXT_WINDOW_TOKENS, DEFAULT_MAX_OUTPUT_TOKENS)));
    }
}
