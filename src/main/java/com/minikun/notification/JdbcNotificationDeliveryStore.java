package com.minikun.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "minikun.planner.enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcNotificationDeliveryStore implements NotificationDeliveryStore {
    private final JdbcTemplate jdbc;

    public JdbcNotificationDeliveryStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc template must not be null");
    }

    @Override
    public void save(NotificationDelivery delivery) {
        jdbc.update("""
                INSERT INTO minikun_notification_delivery
                    (id, source_type, source_id, channel, title, message, status,
                     attempted_at, completed_at, failure_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                delivery.id(), delivery.sourceType(), delivery.sourceId(), delivery.channel().name(),
                delivery.title(), delivery.message(), delivery.status().name(),
                Timestamp.from(delivery.attemptedAt()), Timestamp.from(delivery.completedAt()),
                delivery.failureReason());
    }

    @Override
    public List<NotificationDelivery> list(
            String sourceType,
            String sourceId,
            NotificationDeliveryStatus status,
            int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        StringBuilder sql = new StringBuilder("""
                SELECT id, source_type, source_id, channel, title, message, status,
                       attempted_at, completed_at, failure_reason
                FROM minikun_notification_delivery
                WHERE 1 = 1
                """);
        List<Object> arguments = new ArrayList<>();
        if (sourceType != null && !sourceType.isBlank()) {
            sql.append(" AND source_type = ?");
            arguments.add(sourceType.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (sourceId != null && !sourceId.isBlank()) {
            sql.append(" AND source_id = ?");
            arguments.add(sourceId.trim());
        }
        if (status != null) {
            sql.append(" AND status = ?");
            arguments.add(status.name());
        }
        sql.append(" ORDER BY attempted_at DESC LIMIT ?");
        arguments.add(safeLimit);
        return jdbc.query(sql.toString(), this::map, arguments.toArray());
    }

    private NotificationDelivery map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new NotificationDelivery(
                UUID.fromString(resultSet.getString("id")),
                resultSet.getString("source_type"),
                resultSet.getString("source_id"),
                NotificationChannel.valueOf(resultSet.getString("channel")),
                resultSet.getString("title"),
                resultSet.getString("message"),
                NotificationDeliveryStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("attempted_at").toInstant(),
                resultSet.getTimestamp("completed_at").toInstant(),
                resultSet.getString("failure_reason"));
    }
}
