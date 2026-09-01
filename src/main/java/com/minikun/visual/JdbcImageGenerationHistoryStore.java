package com.minikun.visual;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcImageGenerationHistoryStore implements ImageGenerationHistoryStore {
    private final JdbcTemplate jdbc;

    public JdbcImageGenerationHistoryStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc template must not be null");
    }

    @Override
    public void save(ImageGenerationHistory history) {
        jdbc.update("""
                INSERT INTO minikun_image_generation_history
                    (id, owner_id, conversation_id, origin, illustration_mode, scene_title,
                     prompt, negative_prompt, seed, provider, width, height, steps, image_url, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                history.id(), history.ownerId(), history.conversationId(), history.origin(),
                history.illustrationMode(), history.sceneTitle(), history.prompt(), history.negativePrompt(),
                history.seed(), history.provider(), history.width(), history.height(), history.steps(),
                history.imageUrl(), Timestamp.from(history.createdAt()));
    }

    @Override
    public List<ImageGenerationHistory> find(String ownerId, String conversationId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbc.query("""
                SELECT id, owner_id, conversation_id, origin, illustration_mode, scene_title,
                       prompt, negative_prompt, seed, provider, width, height, steps, image_url, created_at
                FROM minikun_image_generation_history
                WHERE owner_id=? AND conversation_id=?
                ORDER BY created_at DESC
                LIMIT ?
                """, (rs, row) -> new ImageGenerationHistory(
                        rs.getObject("id", UUID.class), rs.getString("owner_id"),
                        rs.getString("conversation_id"), rs.getString("origin"),
                        rs.getString("illustration_mode"), rs.getString("scene_title"),
                        rs.getString("prompt"), rs.getString("negative_prompt"), rs.getLong("seed"),
                        rs.getString("provider"), integer(rs, "width"), integer(rs, "height"),
                        integer(rs, "steps"), rs.getString("image_url"),
                        rs.getTimestamp("created_at").toInstant()), ownerId, conversationId, safeLimit);
    }

    private Integer integer(java.sql.ResultSet result, String column) throws java.sql.SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }
}
