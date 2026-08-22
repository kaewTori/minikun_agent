package com.minikun.investment;

import java.util.Locale;

public enum InvestmentTransactionType {
    BUY,
    SELL,
    DIVIDEND,
    FEE,
    CASH_DEPOSIT,
    CASH_WITHDRAWAL;

    public static InvestmentTransactionType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("investment transaction type must not be blank");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "investment transaction type must be BUY, SELL, DIVIDEND, FEE, CASH_DEPOSIT, or CASH_WITHDRAWAL");
        }
    }

    public boolean securityTrade() {
        return this == BUY || this == SELL;
    }

    public boolean requiresSymbol() {
        return securityTrade() || this == DIVIDEND;
    }
}
