package com.minikun.task;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "minikun.task.enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcTaskStore implements TaskStore {
    private final JdbcTemplate jdbc;

    public JdbcTaskStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc template must not be null");
    }

    @Override
    public PersonalTask create(PersonalTask task) {
        jdbc.update("""
                INSERT INTO minikun_task
                    (id, owner_id, conversation_id, kind, title, description, status, parent_id,
                     due_at, timezone, next_action, waiting_for, follow_up_at, last_follow_up_at,
                     created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, task.id(), task.ownerId(), task.conversationId(), task.kind().name(), task.title(),
                task.description(), task.status().name(), task.parentId(), timestamp(task.dueAt()),
                task.timezone().getId(), task.nextAction(), task.waitingFor(), timestamp(task.followUpAt()),
                timestamp(task.lastFollowUpAt()), timestamp(task.createdAt()), timestamp(task.updatedAt()),
                timestamp(task.completedAt()));
        return task;
    }

    @Override
    public Optional<PersonalTask> find(UUID id, String ownerId) {
        return jdbc.query("""
                SELECT id, owner_id, conversation_id, kind, title, description, status, parent_id,
                       due_at, timezone, next_action, waiting_for, follow_up_at, last_follow_up_at,
                       created_at, updated_at, completed_at
                FROM minikun_task WHERE id = ? AND owner_id = ?
                """, this::map, id, ownerId).stream().findFirst();
    }

    @Override
    public List<PersonalTask> list(String ownerId, TaskStatus status) {
        if (status == null) {
            return jdbc.query("""
                    SELECT id, owner_id, conversation_id, kind, title, description, status, parent_id,
                           due_at, timezone, next_action, waiting_for, follow_up_at, last_follow_up_at,
                           created_at, updated_at, completed_at
                    FROM minikun_task WHERE owner_id = ? ORDER BY status, due_at NULLS LAST, created_at
                    """, this::map, ownerId);
        }
        return jdbc.query("""
                SELECT id, owner_id, conversation_id, kind, title, description, status, parent_id,
                       due_at, timezone, next_action, waiting_for, follow_up_at, last_follow_up_at,
                       created_at, updated_at, completed_at
                FROM minikun_task WHERE owner_id = ? AND status = ?
                ORDER BY due_at NULLS LAST, created_at
                """, this::map, ownerId, status.name());
    }

    @Override
    public List<PersonalTask> findDueFollowUps(Instant now) {
        return jdbc.query("""
                SELECT id, owner_id, conversation_id, kind, title, description, status, parent_id,
                       due_at, timezone, next_action, waiting_for, follow_up_at, last_follow_up_at,
                       created_at, updated_at, completed_at
                FROM minikun_task
                WHERE status IN ('OPEN', 'IN_PROGRESS', 'BLOCKED')
                  AND follow_up_at IS NOT NULL AND follow_up_at <= ?
                  AND (last_follow_up_at IS NULL OR last_follow_up_at < follow_up_at)
                ORDER BY follow_up_at ASC LIMIT 50
                """, this::map, timestamp(now));
    }

    @Override
    public PersonalTask update(PersonalTask task) {
        jdbc.update("""
                UPDATE minikun_task
                SET title = ?, description = ?, status = ?, due_at = ?, timezone = ?, next_action = ?,
                    waiting_for = ?, follow_up_at = ?, updated_at = ?, completed_at = ?
                WHERE id = ? AND owner_id = ?
                """, task.title(), task.description(), task.status().name(), timestamp(task.dueAt()),
                task.timezone().getId(), task.nextAction(), task.waitingFor(), timestamp(task.followUpAt()),
                timestamp(task.updatedAt()), timestamp(task.completedAt()), task.id(), task.ownerId());
        return task;
    }

    @Override
    public boolean delete(UUID id, String ownerId, Instant updatedAt) {
        return jdbc.update("""
                UPDATE minikun_task SET status = 'CANCELLED', updated_at = ?
                WHERE id = ? AND owner_id = ? AND status NOT IN ('DONE', 'CANCELLED')
                """, timestamp(updatedAt), id, ownerId) > 0;
    }

    @Override
    public void markFollowedUp(PersonalTask task, Instant now) {
        jdbc.update("""
                UPDATE minikun_task SET last_follow_up_at = ?, updated_at = ?
                WHERE id = ? AND owner_id = ? AND status IN ('OPEN', 'IN_PROGRESS', 'BLOCKED')
                """, timestamp(now), timestamp(now), task.id(), task.ownerId());
    }

    private PersonalTask map(ResultSet rs, int row) throws SQLException {
        return new PersonalTask(
                UUID.fromString(rs.getString("id")), rs.getString("owner_id"), rs.getString("conversation_id"),
                TaskKind.valueOf(rs.getString("kind")), rs.getString("title"), rs.getString("description"),
                TaskStatus.valueOf(rs.getString("status")), uuid(rs, "parent_id"), instant(rs, "due_at"),
                ZoneId.of(rs.getString("timezone")), rs.getString("next_action"), rs.getString("waiting_for"),
                instant(rs, "follow_up_at"), instant(rs, "last_follow_up_at"), instant(rs, "created_at"),
                instant(rs, "updated_at"), instant(rs, "completed_at"));
    }

    private UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
