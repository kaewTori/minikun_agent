package com.minikun.investment;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** A small, owner-scoped news observation kept for daily investment review. */
public record InvestmentNewsEvent(
        UUID id,
        String ownerId,
        String eventKey,
        String symbol,
        String title,
        String summary,
        String url,
        String source,
        Instant publishedAt,
        Instant discoveredAt,
        String materiality) {

    public InvestmentNewsEvent {
        Objects.requireNonNull(id, "investment news id must not be null");
        ownerId = InvestmentPolicy.requireOwner(ownerId);
        eventKey = required(eventKey, "investment news event key", 128);
        symbol = required(symbol, "investment news symbol", 64).toUpperCase(Locale.ROOT);
        title = required(title, "investment news title", 500);
        summary = required(summary, "investment news summary", 4_000);
        url = required(url, "investment news URL", 2_000);
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new IllegalArgumentException("investment news URL must use HTTP or HTTPS");
        }
        source = required(source, "investment news source", 64).toUpperCase(Locale.ROOT);
        Objects.requireNonNull(discoveredAt, "investment news discovery time must not be null");
        materiality = required(materiality, "investment news materiality", 16).toUpperCase(Locale.ROOT);
        if (!materiality.equals("HIGH") && !materiality.equals("MEDIUM") && !materiality.equals("LOW")) {
            throw new IllegalArgumentException("investment news materiality must be HIGH, MEDIUM, or LOW");
        }
    }

    private static String required(String value, String field, int maximumLength) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must be at most " + maximumLength + " characters");
        }
        return normalized;
    }
}
