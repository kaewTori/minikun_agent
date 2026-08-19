package com.minikun.planner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "minikun.planner.enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcPlannerStore implements PlannerStore {
    private final JdbcTemplate jdbc;

    public JdbcPlannerStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc template must not be null");
    }

    @Override
    public PlannerEvent create(PlannerEvent event) {
        jdbc.update("""
                INSERT INTO minikun_planner_event
                    (id, conversation_id, title, note, starts_at, timezone,
                     remind_before_minutes, recurrence, status, next_notify_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.id(), event.conversationId(), event.title(), event.note(), timestamp(event.startsAt()),
                event.timezone().getId(), event.remindBeforeMinutes(), event.recurrence().name(), event.status(),
                timestamp(event.nextNotifyAt()), timestamp(event.createdAt()), timestamp(event.updatedAt()));
        return event;
    }

    @Override
    public Optional<PlannerEvent> find(UUID id, String conversationId) {
        List<PlannerEvent> events = jdbc.query("""
                SELECT id, conversation_id, title, note, starts_at, timezone,
                       remind_before_minutes, recurrence, status, next_notify_at, created_at, updated_at
                FROM minikun_planner_event
                WHERE id = ? AND conversation_id = ?
                """, this::map, id, conversationId);
        return events.stream().findFirst();
    }

    @Override
    public List<PlannerEvent> list(String conversationId) {
        return jdbc.query("""
                SELECT id, conversation_id, title, note, starts_at, timezone,
                       remind_before_minutes, recurrence, status, next_notify_at, created_at, updated_at
                FROM minikun_planner_event
                WHERE conversation_id = ?
                ORDER BY starts_at ASC
                LIMIT 100
                """, this::map, conversationId);
    }

    @Override
    public List<PlannerEvent> findDue(Instant now) {
        return jdbc.query("""
                SELECT id, conversation_id, title, note, starts_at, timezone,
                       remind_before_minutes, recurrence, status, next_notify_at, created_at, updated_at
                FROM minikun_planner_event
                WHERE status = 'ACTIVE' AND next_notify_at <= ?
                ORDER BY next_notify_at ASC
                LIMIT 50
                """, this::map, timestamp(now));
    }

    @Override
    public List<PlannerEvent> listUpcoming(Instant from, Instant to) {
        return jdbc.query("""
                SELECT id, conversation_id, title, note, starts_at, timezone,
                       remind_before_minutes, recurrence, status, next_notify_at, created_at, updated_at
                FROM minikun_planner_event
                WHERE status = 'ACTIVE' AND starts_at >= ? AND starts_at < ?
                ORDER BY starts_at ASC
                LIMIT 100
                """, this::map, timestamp(from), timestamp(to));
    }

    @Override
    public PlannerEvent update(PlannerEvent event) {
        jdbc.update("""
                UPDATE minikun_planner_event
                SET title = ?, note = ?, starts_at = ?, timezone = ?, remind_before_minutes = ?,
                    recurrence = ?, next_notify_at = ?, updated_at = ?
                WHERE id = ? AND conversation_id = ? AND status = 'ACTIVE'
                """,
                event.title(), event.note(), timestamp(event.startsAt()), event.timezone().getId(),
                event.remindBeforeMinutes(), event.recurrence().name(), timestamp(event.nextNotifyAt()),
                timestamp(event.updatedAt()), event.id(), event.conversationId());
        return event;
    }

    @Override
    public boolean cancel(UUID id, String conversationId, Instant updatedAt) {
        return jdbc.update("""
                UPDATE minikun_planner_event
                SET status = 'CANCELLED', updated_at = ?
                WHERE id = ? AND conversation_id = ? AND status = 'ACTIVE'
                """, timestamp(updatedAt), id, conversationId) > 0;
    }

    @Override
    public void markDelivered(PlannerEvent event, Instant now) {
        if (event.recurrence() == PlannerRecurrence.NONE) {
            jdbc.update("""
                    UPDATE minikun_planner_event
                    SET status = 'DONE', updated_at = ?
                    WHERE id = ? AND status = 'ACTIVE'
                    """, timestamp(now), event.id());
            return;
        }
        long days = event.recurrence() == PlannerRecurrence.DAILY ? 1 : 7;
        jdbc.update("""
                UPDATE minikun_planner_event
                SET starts_at = ?, next_notify_at = ?, updated_at = ?
                WHERE id = ? AND status = 'ACTIVE'
                """, timestamp(event.startsAt().plusSeconds(days * 24 * 60 * 60)),
                timestamp(event.nextNotifyAt().plusSeconds(days * 24 * 60 * 60)), timestamp(now), event.id());
    }

    @Override
    public void recordAction(
            UUID actionId,
            String conversationId,
            UUID eventId,
            String action,
            Instant snoozedUntil,
            Instant createdAt) {
        jdbc.update("""
                INSERT INTO minikun_reminder_action
                    (id, conversation_id, event_id, action, snoozed_until, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, actionId, conversationId, eventId, action,
                snoozedUntil == null ? null : timestamp(snoozedUntil), timestamp(createdAt));
    }

    private PlannerEvent map(ResultSet resultSet, int rowNum) throws SQLException {
        return new PlannerEvent(
                UUID.fromString(resultSet.getString("id")),
                resultSet.getString("conversation_id"),
                resultSet.getString("title"),
                resultSet.getString("note"),
                resultSet.getTimestamp("starts_at").toInstant(),
                ZoneId.of(resultSet.getString("timezone")),
                resultSet.getInt("remind_before_minutes"),
                PlannerRecurrence.valueOf(resultSet.getString("recurrence")),
                resultSet.getString("status"),
                resultSet.getTimestamp("next_notify_at").toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
