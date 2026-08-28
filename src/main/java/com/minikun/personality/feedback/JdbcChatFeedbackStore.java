package com.minikun.personality.feedback;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcChatFeedbackStore implements ChatFeedbackStore {
    private final JdbcTemplate jdbc;
    public JdbcChatFeedbackStore(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override
    public ChatFeedback save(ChatFeedback value) {
        jdbc.update("""
                INSERT INTO minikun_chat_feedback
                    (id, owner_id, conversation_id, message_id, rating, category, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (owner_id, conversation_id, message_id) DO UPDATE SET
                    rating = EXCLUDED.rating, category = EXCLUDED.category,
                    reason = EXCLUDED.reason, created_at = EXCLUDED.created_at
                """, value.id(), value.ownerId(), value.conversationId(), value.messageId(), value.rating(),
                value.category().name(), value.reason(), java.sql.Timestamp.from(value.createdAt()));
        return value;
    }

    @Override
    public List<ChatFeedback> list(String ownerId, int limit) {
        return jdbc.query("""
                SELECT id, owner_id, conversation_id, message_id, rating, category, reason, created_at
                FROM minikun_chat_feedback WHERE owner_id = ? ORDER BY created_at DESC LIMIT ?
                """, (rs, row) -> new ChatFeedback(UUID.fromString(rs.getString("id")), rs.getString("owner_id"),
                rs.getString("conversation_id"), rs.getString("message_id"), rs.getString("rating"),
                ChatFeedbackCategory.valueOf(rs.getString("category")), rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant()), ownerId, limit);
    }

    @Override
    public boolean delete(String ownerId, String conversationId, String messageId) {
        return jdbc.update("DELETE FROM minikun_chat_feedback WHERE owner_id = ? AND conversation_id = ? AND message_id = ?",
                ownerId, conversationId, messageId) > 0;
    }
}
