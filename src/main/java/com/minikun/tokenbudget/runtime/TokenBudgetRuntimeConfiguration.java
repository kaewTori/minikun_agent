package com.minikun.tokenbudget.runtime;

import com.minikun.model.ChatModelId;
import com.minikun.model.capability.DefaultModelCapabilityRegistry;
import com.minikun.model.capability.ModelCapability;
import com.minikun.model.capability.ModelCapabilityRegistry;
import com.minikun.model.capability.ModelRole;
import com.minikun.tokenbudget.config.TokenBudgetProperties;
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
import com.minikun.tokenbudget.pressure.ContextPressureAnalyzer;
import com.minikun.tokenbudget.pressure.ContextPressureRecoveryPolicy;
import com.minikun.tokenbudget.pressure.DefaultContextPressureAnalyzer;
import com.minikun.tokenbudget.pressure.DefaultContextPressureRecoveryPolicy;
import com.minikun.tokenbudget.recovery.ContextRecoveryPlanner;
import com.minikun.tokenbudget.recovery.DefaultContextRecoveryPlanner;
import com.minikun.tokenbudget.recovery.ContextRecoveryPriorityPolicy;
import com.minikun.tokenbudget.recovery.DefaultContextRecoveryPriorityPolicy;
import com.minikun.tokenbudget.recovery.NoOpRecoveryExecutor;
import com.minikun.tokenbudget.recovery.RecoveryExecutor;
import com.minikun.tokenbudget.recovery.DefaultRecoveryStrategyRegistry;
import com.minikun.tokenbudget.recovery.NoOpRecoveryStrategyHandler;
import com.minikun.tokenbudget.recovery.RecoveryStep;
import com.minikun.tokenbudget.recovery.RecoveryStrategyHandler;
import com.minikun.tokenbudget.recovery.RecoveryStrategyRegistry;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TokenBudgetProperties.class)
public class TokenBudgetRuntimeConfiguration {
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
    ContextPressureAnalyzer contextPressureAnalyzer() {
        return new DefaultContextPressureAnalyzer();
    }

    @Bean
    ContextPressureRecoveryPolicy contextPressureRecoveryPolicy() {
        return new DefaultContextPressureRecoveryPolicy();
    }

    @Bean
    ContextRecoveryPlanner contextRecoveryPlanner() {
        return new DefaultContextRecoveryPlanner();
    }

    @Bean
    ContextRecoveryPriorityPolicy contextRecoveryPriorityPolicy() {
        return new DefaultContextRecoveryPriorityPolicy();
    }

    @Bean
    RecoveryExecutor recoveryExecutor() {
        return new NoOpRecoveryExecutor();
    }

    @Bean
    RecoveryStrategyRegistry recoveryStrategyRegistry() {
        RecoveryStrategyHandler noOpHandler = new NoOpRecoveryStrategyHandler();
        return new DefaultRecoveryStrategyRegistry(Map.of(
                RecoveryStep.REDUCE_KNOWLEDGE, noOpHandler,
                RecoveryStep.REDUCE_MEMORY, noOpHandler,
                RecoveryStep.REDUCE_CONVERSATION_HISTORY, noOpHandler,
                RecoveryStep.REQUEST_FALLBACK, noOpHandler));
    }

    @Bean
    DynamicGenerationOptionsFactory dynamicGenerationOptionsFactory(
            DynamicTokenPlanner dynamicTokenPlanner,
            GenerationOptionsResolver generationOptionsResolver,
            TokenBudgetDecisionMapper tokenBudgetDecisionMapper,
            TokenBudgetSafetyPolicy tokenBudgetSafetyPolicy,
            ContextPressureAnalyzer contextPressureAnalyzer,
            ContextPressureRecoveryPolicy contextPressureRecoveryPolicy,
            ContextRecoveryPlanner contextRecoveryPlanner,
            ContextRecoveryPriorityPolicy contextRecoveryPriorityPolicy,
            RecoveryExecutor recoveryExecutor) {
        return new DynamicGenerationOptionsFactory(dynamicTokenPlanner, generationOptionsResolver,
                tokenBudgetDecisionMapper, tokenBudgetSafetyPolicy, contextPressureAnalyzer,
                contextPressureRecoveryPolicy, contextRecoveryPlanner, contextRecoveryPriorityPolicy,
                recoveryExecutor);
    }

    @Bean
    ModelCapabilityRegistry modelCapabilityRegistry(
            @org.springframework.beans.factory.annotation.Value("${minikun.model.capability.existing.context-window:16384}")
            long existingContextWindow,
            @org.springframework.beans.factory.annotation.Value("${minikun.model.capability.existing.max-output:4096}")
            long existingMaxOutput,
            @org.springframework.beans.factory.annotation.Value("${minikun.model.capability.tinygrad.context-window:16384}")
            long tinygradContextWindow,
            @org.springframework.beans.factory.annotation.Value("${minikun.model.capability.tinygrad.max-output:4096}")
            long tinygradMaxOutput) {
        return new DefaultModelCapabilityRegistry(Map.of(
                ChatModelId.EXISTING,
                new ModelCapability(ChatModelId.EXISTING, ModelRole.CHAT,
                        existingContextWindow, existingMaxOutput),
                ChatModelId.TINYGRAD,
                new ModelCapability(ChatModelId.TINYGRAD, ModelRole.CHAT,
                        tinygradContextWindow, tinygradMaxOutput)));
    }
}
