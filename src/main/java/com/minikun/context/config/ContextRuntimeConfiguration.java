package com.minikun.context.config;

import com.minikun.context.recovery.ContextRecoveryPolicy;
import com.minikun.context.recovery.DefaultContextRecoveryPolicy;
import com.minikun.context.runtime.PersonalContextRuntime;
import com.minikun.pcs.PromptComposer;
import com.minikun.tokenbudget.runtime.DynamicGenerationOptionsFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ContextRecoveryProperties.class)
public class ContextRuntimeConfiguration {
    @Bean
    ContextRecoveryPolicy contextRecoveryPolicy(ContextRecoveryProperties properties) {
        return new DefaultContextRecoveryPolicy(
                properties.minimumContextCharacters(), properties.reductionPercent());
    }

    @Bean
    PersonalContextRuntime personalContextRuntime(
            PromptComposer promptComposer,
            DynamicGenerationOptionsFactory dynamicGenerationOptionsFactory,
            MeterRegistry meterRegistry,
            ContextRecoveryPolicy contextRecoveryPolicy) {
        return new PersonalContextRuntime(
                promptComposer, dynamicGenerationOptionsFactory, meterRegistry, contextRecoveryPolicy);
    }
}
