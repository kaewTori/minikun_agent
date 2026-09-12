package com.minikun.investment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Durable state for the read-only investment news monitor. */
public interface InvestmentMonitorStore {
    Optional<String> latestReportJson(String ownerId);

    void saveLatestReport(String ownerId, LocalDate reportDate, Instant updatedAt, String reportJson);

    boolean deliveredOn(String ownerId, LocalDate date);

    void markDelivered(String ownerId, LocalDate date, Instant deliveredAt);

    boolean containsNews(String ownerId, String eventKey);

    void saveNews(InvestmentNewsEvent event);

    List<InvestmentNewsEvent> recentNews(String ownerId, Instant since, int limit);
}
