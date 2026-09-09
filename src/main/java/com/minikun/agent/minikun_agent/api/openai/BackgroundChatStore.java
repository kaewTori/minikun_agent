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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Minimal durable state for resumable background chat turns. */
@Component
final class BackgroundChatStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundChatStore.class);
    private static final long DATABASE_RETRY_NANOS = Duration.ofSeconds(5).toNanos();

    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Map<UUID, SavedJob> memory = new ConcurrentHashMap<>();
    private volatile boolean databaseUnavailable;
    private volatile long nextDatabaseProbeNanos;

    @Autowired
    BackgroundChatStore(ObjectProvider<JdbcTemplate> jdbc, ObjectMapper json) {
        this(jdbc, jdbc == null ? null : jdbc.getIfAvailable(), json);
    }

    BackgroundChatStore(JdbcTemplate jdbc, ObjectMapper json) {
        this(null, jdbc, json);
    }

    private BackgroundChatStore(ObjectProvider<JdbcTemplate> jdbcProvider, JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbcProvider = jdbcProvider;
        this.jdbc = jdbc;
        this.json = json;
    }

    SavedJob create(UUID id, ChatCompletionRequest request, String conversationId) {
        Instant now = Instant.now();
        SavedJob job = new SavedJob(id, conversationId, request, "RUNNING", null, "", now, now);
        memory.put(id, job);
        JdbcTemplate database = database();
        if (database == null) {
            return job;
        }
        try {
            upsert(database, job);
            if (databaseRecovered()) flushMemory(database);
        } catch (DataAccessException exception) {
            databaseFailed(exception);
        }
        return job;
    }

    void update(UUID id, String status, ChatCompletionResponse response, String error) {
        Instant now = Instant.now();
        JdbcTemplate database = database();
        if (database == null) {
            updateMemory(id, status, response, error, now);
            return;
        }
        try {
            SavedJob current = memory.get(id);
            if (current != null) {
                SavedJob updated = updated(current, status, response, error, now);
                memory.put(id, updated);
                upsert(database, updated);
                memory.remove(id, updated);
            } else {
                database.update("UPDATE minikun_background_chat_job SET status = ?, response_json = ?, error = ?, updated_at = ? WHERE id = ?",
                        status, response == null ? null : write(response), clean(error), Timestamp.from(now), id);
            }
            if (databaseRecovered()) flushMemory(database);
        } catch (DataAccessException exception) {
            databaseFailed(exception);
            updateMemory(id, status, response, error, now);
        }
    }

    Optional<SavedJob> find(UUID id) {
        JdbcTemplate database = database();
        if (database == null) return Optional.ofNullable(memory.get(id));
        try {
            Optional<SavedJob> persisted = database.query("SELECT * FROM minikun_background_chat_job WHERE id = ?", this::read, id)
                    .stream().findFirst();
            Optional<SavedJob> buffered = Optional.ofNullable(memory.get(id));
            if (databaseRecovered()) flushMemory(database);
            return buffered.or(() -> persisted);
        } catch (DataAccessException exception) {
            databaseFailed(exception);
            return Optional.ofNullable(memory.get(id));
        }
    }

    List<SavedJob> pending() {
        JdbcTemplate database = database();
        if (database == null) return pendingMemory();
        try {
            List<SavedJob> persisted = database.query(
                    "SELECT * FROM minikun_background_chat_job WHERE status = 'RUNNING' ORDER BY created_at", this::read);
            List<SavedJob> buffered = pendingMemory();
            if (databaseRecovered()) flushMemory(database);
            Map<UUID, SavedJob> merged = new LinkedHashMap<>();
            persisted.forEach(job -> merged.put(job.id(), job));
            buffered.forEach(job -> merged.put(job.id(), job));
            memory.values().stream().filter(job -> "RUNNING".equals(job.status()))
                    .forEach(job -> merged.put(job.id(), job));
            return List.copyOf(merged.values());
        } catch (DataAccessException exception) {
            databaseFailed(exception);
            return pendingMemory();
        }
    }

    void deleteExpired() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        JdbcTemplate database = database();
        if (database == null) {
            deleteExpiredMemory(cutoff);
            return;
        }
        try {
            database.update("DELETE FROM minikun_background_chat_job WHERE status <> 'RUNNING' AND updated_at < ?",
                    Timestamp.from(cutoff));
            if (databaseRecovered()) flushMemory(database);
        } catch (DataAccessException exception) {
            databaseFailed(exception);
            deleteExpiredMemory(cutoff);
        }
    }

    private JdbcTemplate database() {
        JdbcTemplate current = jdbc;
        if (current == null && jdbcProvider != null) {
            current = jdbcProvider.getIfAvailable();
        }
        if (current == null || databaseUnavailable && System.nanoTime() < nextDatabaseProbeNanos) return null;
        return current;
    }

    private boolean databaseRecovered() {
        if (!databaseUnavailable) return false;
        databaseUnavailable = false;
        nextDatabaseProbeNanos = 0;
        LOGGER.info("process=background_chat_storage event=database_recovered");
        return true;
    }

    private void databaseFailed(DataAccessException exception) {
        if (!databaseUnavailable) {
            LOGGER.warn("process=background_chat_storage event=degraded mode=in_memory reason={}",
                    exception.getClass().getSimpleName());
        }
        databaseUnavailable = true;
        nextDatabaseProbeNanos = System.nanoTime() + DATABASE_RETRY_NANOS;
    }

    private void upsert(JdbcTemplate database, SavedJob job) {
        database.update("""
                INSERT INTO minikun_background_chat_job
                    (id, conversation_id, request_json, status, response_json, error, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET conversation_id = EXCLUDED.conversation_id,
                    request_json = EXCLUDED.request_json, status = EXCLUDED.status,
                    response_json = EXCLUDED.response_json, error = EXCLUDED.error,
                    created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at
                """, job.id(), job.conversationId(), write(job.request()), job.status(),
                job.response() == null ? null : write(job.response()), job.error(),
                Timestamp.from(job.createdAt()), Timestamp.from(job.updatedAt()));
    }

    private void flushMemory(JdbcTemplate database) {
        for (Map.Entry<UUID, SavedJob> entry : memory.entrySet()) {
            try {
                upsert(database, entry.getValue());
                memory.remove(entry.getKey(), entry.getValue());
            } catch (DataAccessException exception) {
                databaseFailed(exception);
                return;
            }
        }
    }

    private void updateMemory(UUID id, String status, ChatCompletionResponse response, String error, Instant now) {
        memory.computeIfPresent(id, (ignored, current) -> updated(current, status, response, error, now));
    }

    private SavedJob updated(SavedJob current, String status, ChatCompletionResponse response, String error, Instant now) {
        return new SavedJob(current.id(), current.conversationId(), current.request(), status, response,
                clean(error), current.createdAt(), now);
    }

    private List<SavedJob> pendingMemory() {
        return memory.values().stream().filter(job -> "RUNNING".equals(job.status())).toList();
    }

    private void deleteExpiredMemory(Instant cutoff) {
        memory.values().removeIf(job -> !"RUNNING".equals(job.status()) && job.updatedAt().isBefore(cutoff));
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
