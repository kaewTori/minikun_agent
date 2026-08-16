package com.minikun.tokenbudget.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime settings that affect how input context is reserved for generation.
 * Kept outside the domain package so configuration concerns do not leak into
 * token-budget calculations.
 */
@ConfigurationProperties(prefix = "minikun.token-budget")
public record TokenBudgetProperties(long reservedOutputTokens) {
    public TokenBudgetProperties {
        if (reservedOutputTokens < 0) {
            throw new IllegalArgumentException("reserved output tokens must not be negative");
        }
    }
}
