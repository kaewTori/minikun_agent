package com.minikun.visual;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcCharacterVisualMemory implements CharacterVisualMemory {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final JavaType profileListType;

    public JdbcCharacterVisualMemory(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
        this.profileListType = json.getTypeFactory()
                .constructCollectionType(List.class, CharacterVisualProfile.class);
    }

    @Override
    public List<CharacterVisualProfile> find(String ownerId, String conversationId) {
        return jdbc.query("SELECT profile_json FROM minikun_character_visual_memory "
                        + "WHERE owner_id=? AND conversation_id=?",
                (rs, row) -> decode(rs.getString("profile_json")), required(ownerId), required(conversationId))
                .stream().findFirst().orElse(List.of());
    }

    @Override
    public void save(String ownerId, String conversationId, List<CharacterVisualProfile> profiles) {
        try {
            jdbc.update("""
                    INSERT INTO minikun_character_visual_memory
                        (owner_id, conversation_id, profile_json, updated_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (owner_id, conversation_id) DO UPDATE SET
                        profile_json=EXCLUDED.profile_json, updated_at=EXCLUDED.updated_at
                    """, required(ownerId), required(conversationId),
                    json.writeValueAsString(profiles == null ? List.of() : profiles),
                    Timestamp.from(clock.instant()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize character visual memory", exception);
        }
    }

    private List<CharacterVisualProfile> decode(String value) {
        try {
            return List.copyOf(json.readValue(value, profileListType));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not read character visual memory", exception);
        }
    }

    private String required(String value) {
        String result = value == null ? "" : value.strip();
        if (result.isBlank()) throw new IllegalArgumentException("visual memory scope is required");
        return result;
    }
}
