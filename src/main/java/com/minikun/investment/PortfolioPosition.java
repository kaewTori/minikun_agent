package com.minikun.investment;

import java.math.BigDecimal;

/** Deterministic average-cost position; no external market price is implied. */
public record PortfolioPosition(
        String symbol,
        String instrumentName,
        String assetClass,
        String currency,
        BigDecimal quantity,
        BigDecimal costBasis,
        BigDecimal averageCost,
        BigDecimal costAllocationPercent,
        BigDecimal realizedProfitLoss,
        BigDecimal income,
        BigDecimal fees) {
}
