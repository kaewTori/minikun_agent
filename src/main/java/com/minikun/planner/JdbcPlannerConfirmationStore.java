package com.minikun.planner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** PostgreSQL-backed confirmation state so a pending proposal survives restarts. */
@Component
@ConditionalOnProperty(name = "minikun.planner.enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcPlannerConfirmationStore implements PlannerConfirmationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcPlannerConfirmationStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc template must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
    }

    @Override
    public void save(PendingPlannerConfirmation confirmation) {
        String arguments;
        try {
            arguments = objectMapper.writeValueAsString(confirmation.arguments());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize planner confirmation", exception);
        }
        jdbc.update("""
                INSERT INTO minikun_planner_pending_confirmation
                    (conversation_id, action, arguments_json, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (conversation_id) DO UPDATE SET
                    action = EXCLUDED.action,
                    arguments_json = EXCLUDED.arguments_json,
                    created_at = EXCLUDED.created_at,
                    expires_at = EXCLUDED.expires_at
                """,
                confirmation.conversationId(), confirmation.action(), arguments,
                timestamp(confirmation.createdAt()), timestamp(confirmation.expiresAt()));
    }

    @Override
    public Optional<PendingPlannerConfirmation> find(String conversationId, Instant now) {
        List<PendingPlannerConfirmation> confirmations = jdbc.query("""
                SELECT conversation_id, action, arguments_json, created_at, expires_at
                FROM minikun_planner_pending_confirmation
                WHERE conversation_id = ? AND expires_at > ?
                """, this::map, conversationId, timestamp(now));
        return confirmations.stream().findFirst();
    }

    @Override
    public void clear(String conversationId) {
        jdbc.update("DELETE FROM minikun_planner_pending_confirmation WHERE conversation_id = ?", conversationId);
    }

    private PendingPlannerConfirmation map(ResultSet resultSet, int rowNum) throws SQLException {
        try {
            Map<String, Object> arguments = objectMapper.readValue(
                    resultSet.getString("arguments_json"),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            return new PendingPlannerConfirmation(
                    resultSet.getString("conversation_id"),
                    resultSet.getString("action"),
                    arguments,
                    resultSet.getTimestamp("created_at").toInstant(),
                    resultSet.getTimestamp("expires_at").toInstant());
        } catch (JsonProcessingException exception) {
            throw new SQLException("invalid planner confirmation payload", exception);
        }
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
