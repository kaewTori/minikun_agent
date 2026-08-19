package com.minikun.computer;

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

/** PostgreSQL audit history with a database-free fallback for tests and diagnostics. */
public final class ResilientComputerAuditStore implements ComputerAuditStore {
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final List<ComputerAudit> fallback = new CopyOnWriteArrayList<>();

    public ResilientComputerAuditStore(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbcProvider = Objects.requireNonNull(jdbcProvider, "jdbc provider must not be null");
    }

    @Override
    public void save(ComputerAudit audit) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc != null) {
            try {
                jdbc.update("""
                        INSERT INTO minikun_computer_audit
                            (id, owner_id, conversation_id, operation, target, status, created_at, detail)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, audit.id(), audit.ownerId(), audit.conversationId(), audit.operation(), audit.target(),
                        audit.status(), Timestamp.from(audit.createdAt()), audit.detail());
                return;
            } catch (RuntimeException ignored) {
                // SQL initialization can be disabled in test profiles.
            }
        }
        fallback.add(audit);
    }

    @Override
    public List<ComputerAudit> list(String ownerId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc != null) {
            try {
                return jdbc.query("""
                        SELECT id, owner_id, conversation_id, operation, target, status, created_at, detail
                        FROM minikun_computer_audit WHERE owner_id = ?
                        ORDER BY created_at DESC LIMIT ?
                        """, this::map, ownerId, safeLimit);
            } catch (RuntimeException ignored) {
                // Fall back to local history.
            }
        }
        return fallback.stream().filter(value -> ownerId.equals(value.ownerId()))
                .sorted(Comparator.comparing(ComputerAudit::createdAt).reversed())
                .limit(safeLimit).toList();
    }

    private ComputerAudit map(ResultSet rs, int row) throws SQLException {
        return new ComputerAudit(UUID.fromString(rs.getString("id")), rs.getString("owner_id"),
                rs.getString("conversation_id"), rs.getString("operation"), rs.getString("target"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant(), rs.getString("detail"));
    }
}
