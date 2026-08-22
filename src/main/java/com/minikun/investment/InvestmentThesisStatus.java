package com.minikun.investment;

import java.util.Locale;

public enum InvestmentThesisStatus {
    ACTIVE,
    CLOSED;

    public static InvestmentThesisStatus parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("investment thesis status must be ACTIVE or CLOSED");
        }
    }
}
