package com.minikun.investment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/** Owner-scoped investment rules used by deterministic portfolio checks. */
public record InvestmentPolicy(
        String ownerId,
        String baseCurrency,
        String benchmark,
        BigDecimal maxSinglePositionPercent,
        Instant createdAt,
        Instant updatedAt) {

    public InvestmentPolicy {
        ownerId = requireOwner(ownerId);
        baseCurrency = requireCurrency(baseCurrency);
        benchmark = Objects.requireNonNullElse(benchmark, "").trim().toUpperCase(Locale.ROOT);
        Objects.requireNonNull(maxSinglePositionPercent, "maximum single-position percentage must not be null");
        if (maxSinglePositionPercent.compareTo(BigDecimal.ZERO) <= 0
                || maxSinglePositionPercent.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("maximum single-position percentage must be greater than 0 and at most 100");
        }
        maxSinglePositionPercent = maxSinglePositionPercent.stripTrailingZeros();
        Objects.requireNonNull(createdAt, "investment policy creation time must not be null");
        Objects.requireNonNull(updatedAt, "investment policy update time must not be null");
    }

    static String requireOwner(String value) {
        if (value == null || value.isBlank() || "*".equals(value)) {
            throw new IllegalArgumentException("owner id must not be blank or wildcard");
        }
        return value.trim();
    }

    static String requireCurrency(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be a three-letter ISO code");
        }
        return normalized;
    }
}
