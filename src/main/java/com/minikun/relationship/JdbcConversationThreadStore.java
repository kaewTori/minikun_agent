package com.minikun.relationship;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcConversationThreadStore implements ConversationThreadStore {
    private static final String COLUMNS = """
            id, owner_id, source_conversation_id, topic, summary, last_decision, unresolved_question,
            status, check_in_at, check_in_consent, last_check_in_at, created_at, updated_at
            """;
    private final JdbcTemplate jdbc;

    public JdbcConversationThreadStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public ConversationThread save(ConversationThread value) {
        jdbc.update("""
                INSERT INTO minikun_conversation_thread
                    (id, owner_id, source_conversation_id, topic, topic_fingerprint, summary, last_decision,
                     unresolved_question, status, check_in_at, check_in_consent, last_check_in_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    topic = EXCLUDED.topic, topic_fingerprint = EXCLUDED.topic_fingerprint,
                    summary = EXCLUDED.summary, last_decision = EXCLUDED.last_decision,
                    unresolved_question = EXCLUDED.unresolved_question, status = EXCLUDED.status,
                    check_in_at = EXCLUDED.check_in_at, check_in_consent = EXCLUDED.check_in_consent,
                    last_check_in_at = EXCLUDED.last_check_in_at, updated_at = EXCLUDED.updated_at
                """, value.id(), value.ownerId(), value.sourceConversationId(), value.topic(),
                ConversationThreadService.fingerprint(value.topic()), value.summary(), value.lastDecision(),
                value.unresolvedQuestion(), value.status().name(), ts(value.checkInAt()), value.checkInConsent(),
                ts(value.lastCheckInAt()), ts(value.createdAt()), ts(value.updatedAt()));
        return value;
    }

    @Override
    public Optional<ConversationThread> find(String ownerId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM minikun_conversation_thread WHERE owner_id = ? AND id = ?",
                this::map, ownerId, id).stream().findFirst();
    }

    @Override
    public Optional<ConversationThread> findOpenByFingerprint(String ownerId, String fingerprint) {
        return jdbc.query("SELECT " + COLUMNS + " FROM minikun_conversation_thread "
                        + "WHERE owner_id = ? AND topic_fingerprint = ? AND status = 'OPEN' "
                        + "ORDER BY updated_at DESC LIMIT 1", this::map, ownerId, fingerprint).stream().findFirst();
    }

    @Override
    public List<ConversationThread> list(String ownerId, ConversationThreadStatus status, int limit) {
        if (status == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM minikun_conversation_thread "
                    + "WHERE owner_id = ? ORDER BY updated_at DESC LIMIT ?", this::map, ownerId, limit);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM minikun_conversation_thread "
                        + "WHERE owner_id = ? AND status = ? ORDER BY updated_at DESC LIMIT ?",
                this::map, ownerId, status.name(), limit);
    }

    @Override
    public List<ConversationThread> due(Instant now, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM minikun_conversation_thread "
                        + "WHERE status = 'OPEN' AND check_in_consent = TRUE AND check_in_at <= ? "
                        + "AND (last_check_in_at IS NULL OR last_check_in_at < check_in_at) "
                        + "ORDER BY check_in_at LIMIT ?", this::map, ts(now), limit);
    }

    private ConversationThread map(ResultSet rs, int row) throws SQLException {
        return new ConversationThread(uuid(rs, "id"), rs.getString("owner_id"),
                rs.getString("source_conversation_id"), rs.getString("topic"), rs.getString("summary"),
                rs.getString("last_decision"), rs.getString("unresolved_question"),
                ConversationThreadStatus.valueOf(rs.getString("status")), instant(rs, "check_in_at"),
                rs.getBoolean("check_in_consent"), instant(rs, "last_check_in_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
}
