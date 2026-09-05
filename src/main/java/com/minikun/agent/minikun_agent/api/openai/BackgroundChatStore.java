package com.minikun.agent.minikun_agent.api.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Minimal durable state for resumable background chat turns. */
@Component
final class BackgroundChatStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Map<UUID, SavedJob> memory = new ConcurrentHashMap<>();

    @Autowired
    BackgroundChatStore(ObjectProvider<JdbcTemplate> jdbc, ObjectMapper json) {
        this(jdbc == null ? null : jdbc.getIfAvailable(), json);
    }

    BackgroundChatStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    SavedJob create(UUID id, ChatCompletionRequest request, String conversationId) {
        Instant now = Instant.now();
        SavedJob job = new SavedJob(id, conversationId, request, "RUNNING", null, "", now, now);
        if (jdbc == null) memory.put(id, job);
        else jdbc.update("""
                INSERT INTO minikun_background_chat_job
                    (id, conversation_id, request_json, status, response_json, error, created_at, updated_at)
                VALUES (?, ?, ?, 'RUNNING', NULL, '', ?, ?)
                ON CONFLICT (id) DO UPDATE SET status = 'RUNNING', response_json = NULL,
                    error = '', updated_at = EXCLUDED.updated_at
                """, id, conversationId, write(request), Timestamp.from(now), Timestamp.from(now));
        return job;
    }

    void update(UUID id, String status, ChatCompletionResponse response, String error) {
        Instant now = Instant.now();
        if (jdbc == null) {
            memory.computeIfPresent(id, (ignored, current) -> new SavedJob(current.id(), current.conversationId(),
                    current.request(), status, response, clean(error), current.createdAt(), now));
            return;
        }
        jdbc.update("UPDATE minikun_background_chat_job SET status = ?, response_json = ?, error = ?, updated_at = ? WHERE id = ?",
                status, response == null ? null : write(response), clean(error), Timestamp.from(now), id);
    }

    Optional<SavedJob> find(UUID id) {
        if (jdbc == null) return Optional.ofNullable(memory.get(id));
        return jdbc.query("SELECT * FROM minikun_background_chat_job WHERE id = ?", this::read, id)
                .stream().findFirst();
    }

    List<SavedJob> pending() {
        if (jdbc == null) return memory.values().stream().filter(job -> "RUNNING".equals(job.status())).toList();
        return jdbc.query("SELECT * FROM minikun_background_chat_job WHERE status = 'RUNNING' ORDER BY created_at", this::read);
    }

    void deleteExpired() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        if (jdbc == null) memory.values().removeIf(job -> !"RUNNING".equals(job.status()) && job.updatedAt().isBefore(cutoff));
        else jdbc.update("DELETE FROM minikun_background_chat_job WHERE status <> 'RUNNING' AND updated_at < ?",
                Timestamp.from(cutoff));
    }

    private SavedJob read(ResultSet row, int ignored) throws SQLException {
        return new SavedJob(uuid(row.getObject("id")), row.getString("conversation_id"),
                read(row.getString("request_json"), ChatCompletionRequest.class), row.getString("status"),
                read(row.getString("response_json"), ChatCompletionResponse.class), row.getString("error"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("could not serialize background chat", exception); }
    }

    private <T> T read(String value, Class<T> type) {
        if (value == null || value.isBlank()) return null;
        try { return json.readValue(value, type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("could not restore background chat", exception); }
    }

    private UUID uuid(Object value) { return value instanceof UUID id ? id : UUID.fromString(value.toString()); }
    private String clean(String value) {
        String result = value == null ? "" : value.trim();
        return result.length() <= 1_000 ? result : result.substring(0, 1_000);
    }

    record SavedJob(UUID id, String conversationId, ChatCompletionRequest request, String status,
            ChatCompletionResponse response, String error, Instant createdAt, Instant updatedAt) { }
}
