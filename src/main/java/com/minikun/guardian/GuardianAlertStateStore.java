package com.minikun.guardian;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable alert deduplication with an in-process fallback for database-free profiles. */
public final class GuardianAlertStateStore {
    private static final String STATE_KEY = "homelab-guardian";
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final AtomicReference<GuardianAlertState> fallback = new AtomicReference<>(GuardianAlertState.EMPTY);

    public GuardianAlertStateStore(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbcProvider = Objects.requireNonNull(jdbcProvider, "guardian state jdbc provider must not be null");
    }

    GuardianAlertState load() {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc != null) {
            try {
                List<GuardianAlertState> states = jdbc.query("""
                        SELECT observed_fingerprint, notified_fingerprint, status,
                               consecutive_issues, last_notified_at
                        FROM minikun_guardian_alert_state WHERE state_key = ?
                        """, (rs, row) -> new GuardianAlertState(
                                rs.getString("observed_fingerprint"), rs.getString("notified_fingerprint"),
                                rs.getString("status"), rs.getInt("consecutive_issues"),
                                rs.getTimestamp("last_notified_at") == null ? null
                                        : rs.getTimestamp("last_notified_at").toInstant()), STATE_KEY);
                if (!states.isEmpty()) return states.getFirst();
            } catch (RuntimeException ignored) {
                // SQL initialization may be disabled; preserve correct behavior in memory.
            }
        }
        return fallback.get();
    }

    void save(GuardianAlertState state) {
        fallback.set(state);
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) return;
        try {
            jdbc.update("""
                    INSERT INTO minikun_guardian_alert_state
                        (state_key, observed_fingerprint, notified_fingerprint, status,
                         consecutive_issues, last_notified_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (state_key) DO UPDATE SET
                        observed_fingerprint = EXCLUDED.observed_fingerprint,
                        notified_fingerprint = EXCLUDED.notified_fingerprint,
                        status = EXCLUDED.status,
                        consecutive_issues = EXCLUDED.consecutive_issues,
                        last_notified_at = EXCLUDED.last_notified_at
                    """, STATE_KEY, state.observedFingerprint(), state.notifiedFingerprint(), state.status(),
                    state.consecutiveIssues(), state.lastNotifiedAt() == null ? null : Timestamp.from(state.lastNotifiedAt()));
        } catch (RuntimeException ignored) {
            // Monitoring must continue even when audit persistence is temporarily unavailable.
        }
    }
}
