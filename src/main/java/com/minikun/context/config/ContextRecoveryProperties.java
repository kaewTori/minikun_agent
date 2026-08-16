package com.minikun.context.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "minikun.context.recovery")
public record ContextRecoveryProperties(
        long minimumContextCharacters,
        int reductionPercent) {
    public ContextRecoveryProperties {
        if (minimumContextCharacters < 0) {
            throw new IllegalArgumentException("minimum context characters must not be negative");
        }
        if (reductionPercent < 1 || reductionPercent > 99) {
            throw new IllegalArgumentException("reduction percent must be between 1 and 99");
        }
    }
}
