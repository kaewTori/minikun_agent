package com.minikun.investment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** PostgreSQL-backed monitor state and deduplicated news observations. */
@Component
@ConditionalOnProperty(name = "minikun.investment.enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcInvestmentMonitorStore implements InvestmentMonitorStore {
    private final JdbcTemplate jdbc;

    public JdbcInvestmentMonitorStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc template must not be null");
    }

    @Override
    public Optional<String> latestReportJson(String ownerId) {
        return jdbc.query(
                "SELECT report_json FROM minikun_investment_monitor_state WHERE owner_id = ?",
                (resultSet, rowNum) -> resultSet.getString(1), ownerId).stream().findFirst();
    }

    @Override
    public void saveLatestReport(String ownerId, LocalDate reportDate, Instant updatedAt, String reportJson) {
        jdbc.update("""
                INSERT INTO minikun_investment_monitor_state
                    (owner_id, report_date, report_json, last_delivered_date, last_delivered_at, updated_at)
                VALUES (?, ?, ?, NULL, NULL, ?)
                ON CONFLICT (owner_id) DO UPDATE SET
                    report_date = EXCLUDED.report_date,
                    report_json = EXCLUDED.report_json,
                    updated_at = EXCLUDED.updated_at
                """, ownerId, java.sql.Date.valueOf(reportDate), reportJson, timestamp(updatedAt));
    }

    @Override
    public boolean deliveredOn(String ownerId, LocalDate date) {
        return jdbc.query(
                "SELECT last_delivered_date FROM minikun_investment_monitor_state WHERE owner_id = ?",
                (resultSet, rowNum) -> {
                    java.sql.Date value = resultSet.getDate(1);
                    return value == null ? null : value.toLocalDate();
                }, ownerId).stream().anyMatch(date::equals);
    }

    @Override
    public void markDelivered(String ownerId, LocalDate date, Instant deliveredAt) {
        int updated = jdbc.update("""
                UPDATE minikun_investment_monitor_state
                   SET last_delivered_date = ?, last_delivered_at = ?, updated_at = ?
                 WHERE owner_id = ?
                """, java.sql.Date.valueOf(date), timestamp(deliveredAt), timestamp(deliveredAt), ownerId);
        if (updated == 0) {
            throw new IllegalStateException("investment monitor report must be saved before delivery");
        }
    }

    @Override
    public boolean containsNews(String ownerId, String eventKey) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM minikun_investment_news_event
                 WHERE owner_id = ? AND event_key = ?
                """, Integer.class, ownerId, eventKey);
        return count != null && count > 0;
    }

    @Override
    public void saveNews(InvestmentNewsEvent event) {
        jdbc.update("""
                INSERT INTO minikun_investment_news_event
                    (id, owner_id, event_key, symbol, title, summary, url, source,
                     published_at, discovered_at, materiality)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (owner_id, event_key) DO NOTHING
                """, event.id(), event.ownerId(), event.eventKey(), event.symbol(), event.title(), event.summary(),
                event.url(), event.source(), timestamp(event.publishedAt()), timestamp(event.discoveredAt()),
                event.materiality());
    }

    @Override
    public List<InvestmentNewsEvent> recentNews(String ownerId, Instant since, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("news limit must be between 1 and 100");
        return jdbc.query("""
                SELECT id, owner_id, event_key, symbol, title, summary, url, source,
                       published_at, discovered_at, materiality
                  FROM minikun_investment_news_event
                 WHERE owner_id = ? AND discovered_at >= ?
                 ORDER BY discovered_at DESC, id DESC
                 LIMIT ?
                """, this::mapNews, ownerId, timestamp(since), limit);
    }

    private InvestmentNewsEvent mapNews(ResultSet rs, int row) throws SQLException {
        Object id = rs.getObject("id");
        return new InvestmentNewsEvent(
                id instanceof UUID uuid ? uuid : UUID.fromString(id.toString()), rs.getString("owner_id"),
                rs.getString("event_key"), rs.getString("symbol"), rs.getString("title"),
                rs.getString("summary"), rs.getString("url"), rs.getString("source"),
                instant(rs, "published_at"), instant(rs, "discovered_at"), rs.getString("materiality"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
