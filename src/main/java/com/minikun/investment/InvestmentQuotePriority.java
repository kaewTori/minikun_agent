package com.minikun.investment;

import java.util.Locale;

/** Owner-selected quote refresh priority; it does not express an investment recommendation. */
public enum InvestmentQuotePriority {
    HIGH,
    NORMAL,
    MINOR;

    public static InvestmentQuotePriority parse(String value) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("quote priority must be HIGH, NORMAL, or MINOR");
        }
    }
}
