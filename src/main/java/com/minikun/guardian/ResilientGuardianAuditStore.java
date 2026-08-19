package com.minikun.guardian;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/** Uses PostgreSQL when available and remains usable in database-free diagnostic profiles. */
public final class ResilientGuardianAuditStore implements GuardianAuditStore {
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final List<GuardianActionAudit> fallback = new CopyOnWriteArrayList<>();

    public ResilientGuardianAuditStore(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbcProvider = Objects.requireNonNull(jdbcProvider, "jdbc provider must not be null");
    }

    @Override
    public void save(GuardianActionAudit audit) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) {
            fallback.add(audit);
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO minikun_guardian_action_audit
                        (id, owner_id, conversation_id, action_id, status, created_at, detail)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, audit.id(), audit.ownerId(), audit.conversationId(), audit.actionId(), audit.status(),
                    Timestamp.from(audit.createdAt()), audit.detail());
        } catch (RuntimeException exception) {
            fallback.add(audit);
        }
    }

    @Override
    public List<GuardianActionAudit> list(String ownerId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc != null) {
            try {
                return jdbc.query("""
                        SELECT id, owner_id, conversation_id, action_id, status, created_at, detail
                        FROM minikun_guardian_action_audit
                        WHERE owner_id = ?
                        ORDER BY created_at DESC LIMIT ?
                        """, this::map, ownerId, safeLimit);
            } catch (RuntimeException ignored) {
                // SQL initialization may be disabled in diagnostic profiles; use the local fallback.
            }
        }
        return fallback.stream().filter(value -> ownerId.equals(value.ownerId()))
                .sorted(Comparator.comparing(GuardianActionAudit::createdAt).reversed())
                .limit(safeLimit).toList();
    }

    private GuardianActionAudit map(ResultSet rs, int row) throws SQLException {
        return new GuardianActionAudit(UUID.fromString(rs.getString("id")), rs.getString("owner_id"),
                rs.getString("conversation_id"), rs.getString("action_id"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("detail"));
    }
}
