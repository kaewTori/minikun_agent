package com.minikun.goal;

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(JdbcTemplate.class)
@ConditionalOnProperty(name = "minikun.goal.enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcGoalStore implements GoalStore {
    private final JdbcTemplate jdbc;

    public JdbcGoalStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc template must not be null");
    }

    @Override
    public PersonalGoal create(PersonalGoal goal) {
        jdbc.update("""
                INSERT INTO minikun_goal
                    (id, owner_id, conversation_id, title, description, status, progress_percent,
                     metric, current_value, target_value, next_review_at, timezone, created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, goal.id(), goal.ownerId(), goal.conversationId(), goal.title(), goal.description(),
                goal.status().name(), goal.progressPercent(), goal.metric(), goal.currentValue(), goal.targetValue(),
                timestamp(goal.nextReviewAt()), goal.timezone().getId(), timestamp(goal.createdAt()),
                timestamp(goal.updatedAt()), timestamp(goal.completedAt()));
        return goal;
    }

    @Override
    public Optional<PersonalGoal> find(UUID id, String ownerId) {
        return jdbc.query("SELECT * FROM minikun_goal WHERE id = ? AND owner_id = ?", this::map, id, ownerId)
                .stream().findFirst();
    }

    @Override
    public List<PersonalGoal> list(String ownerId, GoalStatus status) {
        if (status == null) {
            return jdbc.query("SELECT * FROM minikun_goal WHERE owner_id = ? ORDER BY status, next_review_at NULLS LAST, created_at", this::map, ownerId);
        }
        return jdbc.query("SELECT * FROM minikun_goal WHERE owner_id = ? AND status = ? ORDER BY next_review_at NULLS LAST, created_at", this::map, ownerId, status.name());
    }

    @Override
    public List<PersonalGoal> dueForReview(String ownerId, Instant now, int limit) {
        return jdbc.query("""
                SELECT * FROM minikun_goal
                WHERE owner_id = ? AND status = 'ACTIVE' AND next_review_at <= ?
                ORDER BY next_review_at, created_at LIMIT ?
                """, this::map, ownerId, timestamp(now), Math.max(1, Math.min(limit, 500)));
    }

    @Override
    public PersonalGoal update(PersonalGoal goal) {
        jdbc.update("""
                UPDATE minikun_goal SET title = ?, description = ?, status = ?, progress_percent = ?,
                    metric = ?, current_value = ?, target_value = ?, next_review_at = ?, timezone = ?,
                    updated_at = ?, completed_at = ? WHERE id = ? AND owner_id = ?
                """, goal.title(), goal.description(), goal.status().name(), goal.progressPercent(), goal.metric(),
                goal.currentValue(), goal.targetValue(), timestamp(goal.nextReviewAt()), goal.timezone().getId(),
                timestamp(goal.updatedAt()), timestamp(goal.completedAt()), goal.id(), goal.ownerId());
        return goal;
    }

    private PersonalGoal map(ResultSet rs, int row) throws SQLException {
        return new PersonalGoal(UUID.fromString(rs.getString("id")), rs.getString("owner_id"),
                rs.getString("conversation_id"), rs.getString("title"), rs.getString("description"),
                GoalStatus.valueOf(rs.getString("status")), rs.getInt("progress_percent"),
                rs.getString("metric"), rs.getDouble("current_value"), rs.getDouble("target_value"),
                instant(rs, "next_review_at"), ZoneId.of(rs.getString("timezone")),
                instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "completed_at"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
}
