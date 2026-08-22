package com.minikun.investment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Immutable investment ledger entry; positions are derived from these entries. */
public record InvestmentTransaction(
        UUID id,
        String ownerId,
        String conversationId,
        String account,
        InvestmentTransactionType type,
        String symbol,
        String instrumentName,
        String assetClass,
        String currency,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal amount,
        BigDecimal fee,
        Instant occurredAt,
        String note,
        Instant createdAt,
        Instant voidedAt) {

    public InvestmentTransaction {
        Objects.requireNonNull(id, "investment transaction id must not be null");
        ownerId = InvestmentPolicy.requireOwner(ownerId);
        conversationId = requireText(conversationId, "conversation id");
        account = requireText(account, "investment account");
        Objects.requireNonNull(type, "investment transaction type must not be null");
        symbol = Objects.requireNonNullElse(symbol, "").trim().toUpperCase(Locale.ROOT);
        instrumentName = Objects.requireNonNullElse(instrumentName, "").trim();
        assetClass = Objects.requireNonNullElse(assetClass, "OTHER").trim().toUpperCase(Locale.ROOT);
        currency = InvestmentPolicy.requireCurrency(currency);
        quantity = normalize(quantity);
        unitPrice = normalize(unitPrice);
        amount = normalize(amount);
        fee = fee == null ? BigDecimal.ZERO : normalize(fee);
        if (fee.signum() < 0) {
            throw new IllegalArgumentException("investment transaction fee must not be negative");
        }
        if (type.requiresSymbol() && symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required for this investment transaction type");
        }
        if (type.securityTrade()) {
            requirePositive(quantity, "quantity");
            requireNonNegative(unitPrice, "unit price");
            if (amount != null) {
                throw new IllegalArgumentException("amount must be omitted for BUY and SELL transactions");
            }
        } else {
            requirePositive(amount, "amount");
            if (quantity != null || unitPrice != null) {
                throw new IllegalArgumentException("quantity and unit price must be omitted for non-trade transactions");
            }
        }
        Objects.requireNonNull(occurredAt, "investment transaction occurrence time must not be null");
        note = Objects.requireNonNullElse(note, "").trim();
        Objects.requireNonNull(createdAt, "investment transaction creation time must not be null");
    }

    public boolean active() {
        return voidedAt == null;
    }

    public BigDecimal tradeValue() {
        return type.securityTrade() ? quantity.multiply(unitPrice) : amount;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private static void requirePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be greater than zero");
        }
    }

    private static void requireNonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
