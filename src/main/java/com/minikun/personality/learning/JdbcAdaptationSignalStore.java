package com.minikun.personality.learning;

import com.minikun.personality.model.AdaptationSignal;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL-backed evidence store; it never stores raw chat messages. */
public final class JdbcAdaptationSignalStore implements AdaptationSignalStore {
    private final JdbcTemplate jdbc;

    public JdbcAdaptationSignalStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(String ownerId, String dimension, String value, double delta,
            boolean explicit, double competingDecay, Instant observedAt) {
        jdbc.update("""
                UPDATE minikun_adaptation_signal
                SET score = score * ?, updated_at = ?
                WHERE owner_id = ? AND dimension = ? AND candidate_value <> ?
                """, competingDecay, observedAt.atOffset(java.time.ZoneOffset.UTC),
                ownerId, dimension, value);
        jdbc.update("""
                INSERT INTO minikun_adaptation_signal
                    (owner_id, dimension, candidate_value, observations,
                     explicit_observations, score, updated_at)
                VALUES (?, ?, ?, 1, ?, ?, ?)
                ON CONFLICT (owner_id, dimension, candidate_value) DO UPDATE SET
                    observations = minikun_adaptation_signal.observations + 1,
                    explicit_observations = minikun_adaptation_signal.explicit_observations
                        + EXCLUDED.explicit_observations,
                    score = GREATEST(-10, LEAST(10, minikun_adaptation_signal.score + EXCLUDED.score)),
                    updated_at = EXCLUDED.updated_at
                """, ownerId, dimension, value, explicit ? 1 : 0, delta,
                observedAt.atOffset(java.time.ZoneOffset.UTC));
    }

    @Override
    public List<AdaptationSignal> findByOwner(String ownerId) {
        return jdbc.query("""
                SELECT owner_id, dimension, candidate_value, observations,
                       explicit_observations, score, updated_at
                FROM minikun_adaptation_signal
                WHERE owner_id = ?
                ORDER BY dimension, candidate_value
                """, (rs, row) -> new AdaptationSignal(
                rs.getString("owner_id"), rs.getString("dimension"),
                rs.getString("candidate_value"), rs.getInt("observations"),
                rs.getInt("explicit_observations"), rs.getDouble("score"),
                rs.getTimestamp("updated_at").toInstant()), ownerId);
    }

    @Override
    public int deleteAll(String ownerId) {
        return jdbc.update("DELETE FROM minikun_adaptation_signal WHERE owner_id = ?", ownerId);
    }
}
