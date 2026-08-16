package com.minikun.personality.preference;

import com.minikun.personality.model.Preference;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL-backed owner-scoped preference store. */
public final class JdbcPreferenceStore implements PreferenceStore {
    private final JdbcTemplate jdbc;
    public JdbcPreferenceStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<Preference> findByOwner(String ownerId) {
        return jdbc.query("""
                SELECT owner_id, preference_key, preference_value, confidence, updated_at
                FROM minikun_user_preference WHERE owner_id = ?
                ORDER BY preference_key
                """, (rs, row) -> new Preference(rs.getString("owner_id"),
                rs.getString("preference_key"), rs.getString("preference_value"),
                rs.getDouble("confidence"), rs.getTimestamp("updated_at").toInstant()), ownerId);
    }

    @Override
    public void save(Preference preference) {
        jdbc.update("""
                INSERT INTO minikun_user_preference
                    (owner_id, preference_key, preference_value, confidence, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (owner_id, preference_key) DO UPDATE SET
                    preference_value = EXCLUDED.preference_value,
                    confidence = EXCLUDED.confidence,
                    updated_at = EXCLUDED.updated_at
                """, preference.ownerId(), preference.key(), preference.value(),
                preference.confidence(), preference.updatedAt().atOffset(java.time.ZoneOffset.UTC));
    }

    @Override
    public boolean delete(String ownerId, String key) {
        return jdbc.update("DELETE FROM minikun_user_preference WHERE owner_id = ? AND preference_key = ?",
                ownerId, key) > 0;
    }

    @Override
    public int deleteAll(String ownerId) {
        return jdbc.update("DELETE FROM minikun_user_preference WHERE owner_id = ?", ownerId);
    }
}
