package com.minikun.personality.profile;

import com.minikun.personality.model.UserProfile;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL-backed owner-scoped profile store. */
public final class JdbcUserProfileStore implements UserProfileStore {
    private final JdbcTemplate jdbc;
    public JdbcUserProfileStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public UserProfile find(String ownerId) {
        List<UserProfile> profiles = jdbc.query("""
                SELECT owner_id, display_name, preferred_language, response_style, timezone
                FROM minikun_user_profile WHERE owner_id = ?
                """, (rs, row) -> new UserProfile(rs.getString("owner_id"),
                rs.getString("display_name"), rs.getString("preferred_language"),
                rs.getString("response_style"), rs.getString("timezone")), ownerId);
        return profiles.isEmpty() ? new UserProfile(ownerId, "", "", "", "") : profiles.getFirst();
    }

    @Override
    public void save(UserProfile profile) {
        jdbc.update("""
                INSERT INTO minikun_user_profile
                    (owner_id, display_name, preferred_language, response_style, timezone, updated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (owner_id) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    preferred_language = EXCLUDED.preferred_language,
                    response_style = EXCLUDED.response_style,
                    timezone = EXCLUDED.timezone,
                    updated_at = CURRENT_TIMESTAMP
                """, profile.ownerId(), profile.displayName(), profile.preferredLanguage(),
                profile.responseStyle(), profile.timezone());
    }
}
