package com.minikun.investment;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** A durable decision journal entry that records why an owner follows an instrument. */
public record InvestmentThesis(
        UUID id,
        String ownerId,
        String conversationId,
        String symbol,
        String summary,
        String invalidation,
        InvestmentThesisStatus status,
        Instant nextReviewAt,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt) {

    public InvestmentThesis {
        Objects.requireNonNull(id, "investment thesis id must not be null");
        ownerId = InvestmentPolicy.requireOwner(ownerId);
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversation id must not be blank");
        }
        conversationId = conversationId.trim();
        symbol = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        if (symbol.isBlank()) throw new IllegalArgumentException("investment thesis symbol must not be blank");
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("investment thesis summary must not be blank");
        }
        summary = summary.trim();
        invalidation = Objects.requireNonNullElse(invalidation, "").trim();
        Objects.requireNonNull(status, "investment thesis status must not be null");
        Objects.requireNonNull(createdAt, "investment thesis creation time must not be null");
        Objects.requireNonNull(updatedAt, "investment thesis update time must not be null");
        if (status == InvestmentThesisStatus.CLOSED && closedAt == null) {
            throw new IllegalArgumentException("closed investment thesis must have closedAt");
        }
        if (status == InvestmentThesisStatus.ACTIVE && closedAt != null) {
            throw new IllegalArgumentException("active investment thesis must not have closedAt");
        }
    }
}
