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
        String goal,
        String timeHorizon,
        String riskTolerance,
        Instant createdAt,
        Instant updatedAt) {

    /** Keeps source compatibility with the original ledger-only policy. */
    public InvestmentPolicy(
            String ownerId,
            String baseCurrency,
            String benchmark,
            BigDecimal maxSinglePositionPercent,
            Instant createdAt,
            Instant updatedAt) {
        this(ownerId, baseCurrency, benchmark, maxSinglePositionPercent, "", "", "", createdAt, updatedAt);
    }

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
        goal = boundedText(goal, "investment goal", 500);
        timeHorizon = boundedText(timeHorizon, "investment time horizon", 64);
        riskTolerance = boundedText(riskTolerance, "investment risk tolerance", 64);
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

    private static String boundedText(String value, String field, int maximumLength) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must be at most " + maximumLength + " characters");
        }
        return normalized;
    }
}
