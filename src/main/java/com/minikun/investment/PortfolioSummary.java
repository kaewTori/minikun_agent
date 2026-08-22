package com.minikun.investment;

import java.math.BigDecimal;
import java.util.List;

/** Portfolio summary based only on the immutable ledger and cost basis. */
public record PortfolioSummary(
        String ownerId,
        String baseCurrency,
        String allocationBasis,
        BigDecimal totalOpenCostBasis,
        BigDecimal realizedProfitLoss,
        BigDecimal income,
        BigDecimal fees,
        BigDecimal netCashContribution,
        List<PortfolioPosition> positions,
        InvestmentPolicy policy,
        List<String> warnings) {
}
