package com.minikun.personality.companion;

import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcCompanionModeStore implements CompanionModeStore {
    private final JdbcTemplate jdbc;
    public JdbcCompanionModeStore(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }
    @Override public Optional<CompanionMode> find(String ownerId, String conversationId) {
        return jdbc.query("SELECT mode FROM minikun_companion_mode WHERE owner_id = ? AND conversation_id = ?",
                (rs, row) -> CompanionMode.valueOf(rs.getString("mode")), ownerId, conversationId).stream().findFirst();
    }
    @Override public void save(String ownerId, String conversationId, CompanionMode mode) {
        jdbc.update("""
                INSERT INTO minikun_companion_mode (owner_id, conversation_id, mode, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (owner_id, conversation_id) DO UPDATE SET
                    mode = EXCLUDED.mode, updated_at = CURRENT_TIMESTAMP
                """, ownerId, conversationId, mode.name());
    }
    @Override public void delete(String ownerId, String conversationId) {
        jdbc.update("DELETE FROM minikun_companion_mode WHERE owner_id = ? AND conversation_id = ?",
                ownerId, conversationId);
    }
}
