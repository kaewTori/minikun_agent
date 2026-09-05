package com.minikun.tokenbudget.runtime;

import com.minikun.model.ChatModelId;
import com.minikun.model.capability.DefaultModelCapabilityRegistry;
import com.minikun.model.capability.ModelCapability;
import com.minikun.model.capability.ModelCapabilityRegistry;
import com.minikun.model.capability.ModelRole;
import com.minikun.tokenbudget.config.TokenBudgetProperties;
import com.minikun.tokenbudget.counter.ApproximateTokenCounter;
import com.minikun.tokenbudget.counter.TokenCounter;
import com.minikun.tokenbudget.integration.DefaultGenerationOptionsResolver;
import com.minikun.tokenbudget.integration.GenerationOptionsResolver;
import com.minikun.tokenbudget.planner.DefaultDynamicTokenPlanner;
import com.minikun.tokenbudget.planner.DynamicTokenPlanner;
import com.minikun.tokenbudget.policy.DefaultTokenBudgetPolicy;
import com.minikun.tokenbudget.policy.TokenBudgetPolicy;

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
    DynamicGenerationOptionsFactory dynamicGenerationOptionsFactory(
            DynamicTokenPlanner dynamicTokenPlanner,
            GenerationOptionsResolver generationOptionsResolver) {
        return new DynamicGenerationOptionsFactory(dynamicTokenPlanner, generationOptionsResolver);
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
