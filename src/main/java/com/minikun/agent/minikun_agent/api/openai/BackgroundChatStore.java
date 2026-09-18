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
import java.util.Locale;
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
        return create(id, request, conversationId, "", owner(request), Instant.MAX);
    }

    SavedJob create(UUID id, ChatCompletionRequest request, String conversationId,
            String idempotencyKey, String ownerId) {
        return create(id, request, conversationId, idempotencyKey, ownerId, Instant.MAX);
    }

    SavedJob create(UUID id, ChatCompletionRequest request, String conversationId,
            String idempotencyKey, String ownerId, Instant deadlineAt) {
        return create(id, request, conversationId, idempotencyKey, ownerId, deadlineAt, "RUNNING");
    }

    SavedJob create(UUID id, ChatCompletionRequest request, String conversationId,
            String idempotencyKey, String ownerId, Instant deadlineAt, String status) {
        Instant now = Instant.now();
        SavedJob job = new SavedJob(id, conversationId, request,
                status == null || status.isBlank() ? "RUNNING" : status.strip().toUpperCase(Locale.ROOT), null, "",
                "NOT_REQUESTED", "", now, now, cleanKey(idempotencyKey), cleanOwner(ownerId),
                deadlineAt == null ? Instant.MAX : deadlineAt);
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

    Optional<SavedJob> findByIdempotency(String ownerId, String conversationId, String idempotencyKey) {
        String key = cleanKey(idempotencyKey);
        if (key.isBlank()) return Optional.empty();
        String owner = cleanOwner(ownerId);
        String conversation = conversationId == null ? "" : conversationId.strip();
        Optional<SavedJob> buffered = memory.values().stream()
                .filter(job -> owner.equals(job.ownerId())
                        && conversation.equals(job.conversationId())
                        && key.equals(job.idempotencyKey()))
                .findFirst();
        if (buffered.isPresent()) return buffered;
        JdbcTemplate database = database();
        if (database == null) return Optional.empty();
        try {
            Optional<SavedJob> persisted = database.query(
                    "SELECT * FROM minikun_background_chat_job WHERE owner_id = ? AND conversation_id = ? "
                            + "AND idempotency_key = ? ORDER BY created_at LIMIT 1",
                    this::read, owner, conversation, key).stream().findFirst();
            if (databaseRecovered()) flushMemory(database);
            return persisted;
        } catch (DataAccessException exception) {
            databaseFailed(exception);
            return Optional.empty();
        }
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

    /** Commits the text result and the follow-up image state together for restart recovery. */
    void complete(UUID id, ChatCompletionResponse response, boolean imageQueued) {
        Instant now = Instant.now();
        String nextImageStatus = imageQueued ? "QUEUED" : "NOT_REQUESTED";
        JdbcTemplate database = database();
        if (database == null) {
            memory.computeIfPresent(id, (ignored, current) -> completed(current, response, nextImageStatus, now));
            return;
        }
        try {
            SavedJob current = memory.get(id);
            if (current != null) {
                SavedJob updated = completed(current, response, nextImageStatus, now);
                memory.put(id, updated);
                upsert(database, updated);
                memory.remove(id, updated);
            } else {
                database.update("UPDATE minikun_background_chat_job SET status = 'COMPLETED', response_json = ?, "
                                + "error = '', image_status = ?, image_error = '', image_updated_at = ?, "
                                + "updated_at = ? WHERE id = ?",
                        response == null ? null : write(response), nextImageStatus,
                        Timestamp.from(now), Timestamp.from(now), id);
            }
            if (databaseRecovered()) flushMemory(database);
        } catch (DataAccessException exception) {
            databaseFailed(exception);
            memory.computeIfPresent(id, (ignored, current) -> completed(current, response, nextImageStatus, now));
        }
    }

    void reschedule(UUID id, Instant deadlineAt) {
        Instant now = Instant.now();
        Instant deadline = deadlineAt == null ? Instant.MAX : deadlineAt;
        JdbcTemplate database = database();
        if (database == null) {
            memory.computeIfPresent(id, (ignored, current) -> withDeadline(current, deadline, now));
            return;
        }
        try {
            SavedJob current = memory.get(id);
            if (current != null) {
                SavedJob updated = withDeadline(current, deadline, now);
                memory.put(id, updated);
                upsert(database, updated);
                memory.remove(id, updated);
            } else {
                database.update("UPDATE minikun_background_chat_job SET deadline_at = ?, updated_at = ? WHERE id = ?",
                        timestamp(deadline), Timestamp.from(now), id);
            }
            if (databaseRecovered()) flushMemory(database);
        } catch (DataAccessException exception) {
            databaseFailed(exception);
            memory.computeIfPresent(id, (ignored, current) -> withDeadline(current, deadline, now));
        }
    }

    void updateImage(UUID id, String imageStatus, ChatCompletionResponse response, String imageError) {
        Instant now = Instant.now();
        JdbcTemplate database = database();
        if (database == null) {
            updateImageMemory(id, imageStatus, response, imageError, now);
            return;
        }
        try {
            SavedJob current = memory.get(id);
            if (current != null) {
                SavedJob updated = imageUpdated(current, imageStatus, response, imageError, now);
                memory.put(id, updated);
                upsert(database, updated);
                memory.remove(id, updated);
            } else {
                database.update("UPDATE minikun_background_chat_job SET image_status = ?, response_json = ?, "
                                + "image_error = ?, image_updated_at = ?, updated_at = ? WHERE id = ?",
                        normalizeImageStatus(imageStatus), response == null ? null : write(response),
                        clean(imageError), Timestamp.from(now), Timestamp.from(now), id);
            }
            if (databaseRecovered()) flushMemory(database);
        } catch (DataAccessException exception) {
            databaseFailed(exception);
            updateImageMemory(id, imageStatus, response, imageError, now);
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
                    "SELECT * FROM minikun_background_chat_job WHERE status IN ('RUNNING', 'QUEUED') "
                            + "OR image_status IN ('QUEUED', 'RUNNING') ORDER BY created_at", this::read);
            List<SavedJob> buffered = pendingMemory();
            if (databaseRecovered()) flushMemory(database);
            Map<UUID, SavedJob> merged = new LinkedHashMap<>();
            persisted.forEach(job -> merged.put(job.id(), job));
            buffered.forEach(job -> merged.put(job.id(), job));
            memory.values().stream().filter(this::pending)
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
            database.update("DELETE FROM minikun_background_chat_job WHERE status NOT IN ('RUNNING', 'QUEUED') "
                            + "AND image_status NOT IN ('QUEUED', 'RUNNING') AND updated_at < ?",
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
                    (id, conversation_id, request_json, status, response_json, error,
                     image_status, image_error, image_updated_at, idempotency_key, owner_id, deadline_at,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET conversation_id = EXCLUDED.conversation_id,
                    request_json = EXCLUDED.request_json, status = EXCLUDED.status,
                    response_json = EXCLUDED.response_json, error = EXCLUDED.error,
                    image_status = EXCLUDED.image_status, image_error = EXCLUDED.image_error,
                    image_updated_at = EXCLUDED.image_updated_at, idempotency_key = EXCLUDED.idempotency_key,
                    owner_id = EXCLUDED.owner_id, deadline_at = EXCLUDED.deadline_at,
                    created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at
                """, job.id(), job.conversationId(), write(persistedRequest(job.request())), job.status(),
                job.response() == null ? null : write(job.response()), job.error(), job.imageStatus(), job.imageError(),
                Timestamp.from(job.updatedAt()), job.idempotencyKey(), job.ownerId(), timestamp(job.deadlineAt()),
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
                clean(error), current.imageStatus(), current.imageError(), current.createdAt(), now,
                current.idempotencyKey(), current.ownerId(), current.deadlineAt());
    }

    private SavedJob completed(SavedJob current, ChatCompletionResponse response,
            String imageStatus, Instant now) {
        return new SavedJob(current.id(), current.conversationId(), current.request(), "COMPLETED", response,
                "", imageStatus, "", current.createdAt(), now,
                current.idempotencyKey(), current.ownerId(), current.deadlineAt());
    }

    private SavedJob withDeadline(SavedJob current, Instant deadline, Instant now) {
        return new SavedJob(current.id(), current.conversationId(), current.request(), current.status(),
                current.response(), current.error(), current.imageStatus(), current.imageError(),
                current.createdAt(), now, current.idempotencyKey(), current.ownerId(), deadline);
    }

    private SavedJob imageUpdated(SavedJob current, String imageStatus, ChatCompletionResponse response,
            String imageError, Instant now) {
        return new SavedJob(current.id(), current.conversationId(), current.request(), current.status(),
                response == null ? current.response() : response, current.error(), normalizeImageStatus(imageStatus),
                clean(imageError), current.createdAt(), now, current.idempotencyKey(), current.ownerId(),
                current.deadlineAt());
    }

    private void updateImageMemory(UUID id, String imageStatus, ChatCompletionResponse response,
            String imageError, Instant now) {
        memory.computeIfPresent(id, (ignored, current) -> imageUpdated(current, imageStatus, response, imageError, now));
    }

    private List<SavedJob> pendingMemory() {
        return memory.values().stream().filter(this::pending).toList();
    }

    private boolean pending(SavedJob job) {
        return "RUNNING".equals(job.status()) || "QUEUED".equals(job.status())
                || "QUEUED".equals(job.imageStatus())
                || "RUNNING".equals(job.imageStatus());
    }

    private void deleteExpiredMemory(Instant cutoff) {
        memory.values().removeIf(job -> !pending(job) && job.updatedAt().isBefore(cutoff));
    }

    private SavedJob read(ResultSet row, int ignored) throws SQLException {
        return new SavedJob(uuid(row.getObject("id")), row.getString("conversation_id"),
                read(row.getString("request_json"), ChatCompletionRequest.class), row.getString("status"),
                read(row.getString("response_json"), ChatCompletionResponse.class), row.getString("error"),
                normalizeImageStatus(row.getString("image_status")), row.getString("image_error"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                row.getString("idempotency_key"), row.getString("owner_id"),
                row.getTimestamp("deadline_at") == null ? Instant.MAX : row.getTimestamp("deadline_at").toInstant());
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("could not serialize background chat", exception); }
    }

    private ChatCompletionRequest persistedRequest(ChatCompletionRequest request) {
        return request == null ? null : request.withoutDeviceLocation();
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

    private Timestamp timestamp(Instant value) {
        return value == null || Instant.MAX.equals(value) ? null : Timestamp.from(value);
    }

    private String cleanKey(String value) {
        String result = value == null ? "" : value.strip();
        return result.length() <= 200 ? result : result.substring(0, 200);
    }

    private String cleanOwner(String value) {
        String result = value == null ? "" : value.strip();
        return result.length() <= 200 ? result : result.substring(0, 200);
    }

    private String owner(ChatCompletionRequest request) {
        return request == null ? "" : cleanOwner(request.owner_id());
    }

    private String normalizeImageStatus(String value) {
        String result = value == null || value.isBlank() ? "NOT_REQUESTED" : value.strip();
        return result.toUpperCase(Locale.ROOT);
    }

    record SavedJob(UUID id, String conversationId, ChatCompletionRequest request, String status,
            ChatCompletionResponse response, String error, String imageStatus, String imageError,
            Instant createdAt, Instant updatedAt, String idempotencyKey, String ownerId, Instant deadlineAt) {
        SavedJob(UUID id, String conversationId, ChatCompletionRequest request, String status,
                ChatCompletionResponse response, String error, Instant createdAt, Instant updatedAt) {
            this(id, conversationId, request, status, response, error, "NOT_REQUESTED", "",
                    createdAt, updatedAt, "", request == null ? "" : request.owner_id(), Instant.MAX);
        }
    }
}
