package com.minikun.sync;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JdbcChatSyncRepository implements ChatSyncRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<List<Map<String, Object>>> ATTACHMENT_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcChatSyncRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ChatSyncConversation> list(String ownerId, int limit) {
        List<ChatSyncConversation> conversations = jdbc.query("""
                SELECT id, title, updated_at
                FROM minikun_chat_conversation
                WHERE owner_id = ?
                ORDER BY updated_at DESC
                LIMIT ?
                """, (rs, row) -> new ChatSyncConversation(rs.getString("id"), rs.getString("title"),
                        rs.getTimestamp("updated_at").toInstant(), new ArrayList<>()), ownerId, limit);
        if (conversations.isEmpty()) return List.of();

        Map<String, List<ChatSyncConversation.Message>> messages = new LinkedHashMap<>();
        for (ChatSyncConversation conversation : conversations) messages.put(conversation.id(), new ArrayList<>());
        jdbc.query("""
                SELECT conversation_id, id, role, content, files_json, attachments_json,
                       usage_json, timing_json, created_at
                FROM minikun_chat_message
                WHERE owner_id = ?
                ORDER BY created_at, id
                """, rs -> {
                    List<ChatSyncConversation.Message> target = messages.get(rs.getString("conversation_id"));
                    if (target != null) target.add(mapMessage(rs));
                }, ownerId);
        return conversations.stream().map(value -> new ChatSyncConversation(
                value.id(), value.title(), value.updatedAt(), List.copyOf(messages.get(value.id())))).toList();
    }

    @Override
    @Transactional
    public void upsert(String ownerId, ChatSyncConversation conversation) {
        Instant createdAt = conversation.messages().stream().map(ChatSyncConversation.Message::createdAt)
                .min(Instant::compareTo).orElse(conversation.updatedAt());
        jdbc.update("""
                INSERT INTO minikun_chat_conversation (owner_id, id, title, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (owner_id, id) DO UPDATE SET
                    title = CASE
                        WHEN EXCLUDED.updated_at >= minikun_chat_conversation.updated_at THEN EXCLUDED.title
                        ELSE minikun_chat_conversation.title
                    END,
                    updated_at = GREATEST(EXCLUDED.updated_at, minikun_chat_conversation.updated_at)
                """, ownerId, conversation.id(), conversation.title(), timestamp(createdAt),
                timestamp(conversation.updatedAt()));
        for (ChatSyncConversation.Message message : conversation.messages()) {
            jdbc.update("""
                    INSERT INTO minikun_chat_message
                        (owner_id, conversation_id, id, role, content, files_json, attachments_json,
                         usage_json, timing_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (owner_id, conversation_id, id) DO UPDATE SET
                        role = EXCLUDED.role,
                        content = EXCLUDED.content,
                        files_json = EXCLUDED.files_json,
                        attachments_json = EXCLUDED.attachments_json,
                        usage_json = EXCLUDED.usage_json,
                        timing_json = EXCLUDED.timing_json
                    """, ownerId, conversation.id(), message.id(), message.role(), message.content(),
                    json(message.files()), json(message.attachments()), nullableJson(message.usage()),
                    nullableJson(message.timing()), timestamp(message.createdAt()));
        }
    }

    @Override
    @Transactional
    public boolean delete(String ownerId, String conversationId) {
        return jdbc.update("DELETE FROM minikun_chat_conversation WHERE owner_id = ? AND id = ?",
                ownerId, conversationId) > 0;
    }

    @Override
    @Transactional
    public void deleteAll(String ownerId) {
        jdbc.update("DELETE FROM minikun_chat_conversation WHERE owner_id = ?", ownerId);
    }

    private ChatSyncConversation.Message mapMessage(ResultSet rs) throws SQLException {
        return new ChatSyncConversation.Message(rs.getString("id"), rs.getString("role"),
                rs.getString("content"), read(rs.getString("files_json"), STRING_LIST, List.of()),
                read(rs.getString("attachments_json"), ATTACHMENT_LIST, List.of()),
                read(rs.getString("usage_json"), OBJECT_MAP, null),
                read(rs.getString("timing_json"), OBJECT_MAP, null),
                rs.getTimestamp("created_at").toInstant());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Collections.emptyList() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("chat sync metadata is invalid", exception);
        }
    }

    private String nullableJson(Object value) {
        return value == null ? null : json(value);
    }

    private <T> T read(String value, TypeReference<T> type, T fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            return fallback;
        }
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
