package com.minikun.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.model.CompletedConversation;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Persists reflection work before returning from a chat turn and resumes expired leases. */
@Service
public final class DeferredReflectionService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DeferredReflectionService.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final TypeReference<List<CompletedConversation.Message>> MESSAGES = new TypeReference<>() {};
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "minikun-reflection");
        thread.setDaemon(true);
        return thread;
    });
    private final Semaphore fallbackCapacity = new Semaphore(2);
    private final AtomicBoolean busy = new AtomicBoolean();
    private final JdbcTemplate jdbc;
    private final ObjectProvider<ReflectionService> reflectionProvider;
    private final ObjectMapper json;
    private final MeterRegistry metrics;

    @Autowired
    public DeferredReflectionService(ObjectProvider<JdbcTemplate> jdbc,
            ObjectProvider<ReflectionService> reflectionProvider, ObjectMapper json, MeterRegistry metrics) {
        this(jdbc.getIfAvailable(), reflectionProvider, json, metrics);
    }

    public DeferredReflectionService() {
        this((JdbcTemplate) null, null, new ObjectMapper(), null);
    }

    DeferredReflectionService(JdbcTemplate jdbc, ObjectProvider<ReflectionService> reflectionProvider,
            ObjectMapper json, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.reflectionProvider = reflectionProvider;
        this.json = Objects.requireNonNull(json);
        this.metrics = metrics;
    }

    public boolean submit(ReflectionService service, CompletedConversation conversation, String requestId) {
        Objects.requireNonNull(service);
        Objects.requireNonNull(conversation);
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("request id is required");
        if (jdbc == null) return submit(() -> service.reflect(conversation));
        try {
            int inserted = jdbc.update("""
                    INSERT INTO minikun_reflection_job
                        (id, owner_id, conversation_id, request_id, messages_json, observed_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (owner_id, conversation_id, request_id) DO NOTHING
                    """, UUID.randomUUID(), conversation.ownerId(), conversation.conversationId(),
                    requestId, json.writeValueAsString(conversation.messages()),
                    Timestamp.from(conversation.observedAt()));
            count(inserted == 1 ? "enqueued" : "duplicate");
            return true;
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("memory_reflection_queue enqueue_failed reason_type={}", exception.getClass().getSimpleName());
            count("enqueue_failed");
            return submit(() -> service.reflect(conversation));
        }
    }

    /** Local-only fallback while the database is unavailable. */
    public boolean submit(Runnable reflectionTask) {
        Objects.requireNonNull(reflectionTask);
        if (!fallbackCapacity.tryAcquire()) return false;
        try {
            executor.execute(() -> {
                try { reflectionTask.run(); }
                finally { fallbackCapacity.release(); }
            });
            return true;
        } catch (RuntimeException exception) {
            fallbackCapacity.release();
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${minikun.memory.reflection.queue.poll-ms:5000}")
    public void poll() {
        if (jdbc == null || reflectionProvider == null || !busy.compareAndSet(false, true)) return;
        boolean dispatched = false;
        try {
            ReflectionService service = reflectionProvider.getIfAvailable();
            if (service == null) return;
            jdbc.update("""
                    UPDATE minikun_reflection_job SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                    WHERE status = 'RUNNING' AND attempts >= ? AND lease_until < CURRENT_TIMESTAMP
                    """, MAX_ATTEMPTS);
            List<Job> claimed = jdbc.query("""
                    WITH next_job AS (
                        SELECT id FROM minikun_reflection_job
                        WHERE attempts < ? AND
                            ((status = 'QUEUED' AND available_at <= CURRENT_TIMESTAMP)
                             OR (status = 'RUNNING' AND lease_until < CURRENT_TIMESTAMP))
                        ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE minikun_reflection_job AS job
                    SET status = 'RUNNING', attempts = job.attempts + 1,
                        lease_until = CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                        updated_at = CURRENT_TIMESTAMP
                    FROM next_job WHERE job.id = next_job.id
                    RETURNING job.id, job.owner_id, job.conversation_id, job.messages_json,
                              job.observed_at, job.attempts
                    """, (rs, row) -> new Job(rs.getObject("id", UUID.class), rs.getString("owner_id"),
                    rs.getString("conversation_id"), rs.getString("messages_json"),
                    rs.getTimestamp("observed_at").toInstant(), rs.getInt("attempts")), MAX_ATTEMPTS);
            if (claimed.isEmpty()) return;
            executor.execute(() -> run(service, claimed.getFirst()));
            dispatched = true;
        } catch (RuntimeException exception) {
            log.warn("memory_reflection_queue poll_failed reason_type={}", exception.getClass().getSimpleName());
            count("poll_failed");
        } finally {
            if (!dispatched) busy.set(false);
        }
    }

    private void run(ReflectionService service, Job job) {
        boolean success = false;
        try {
            List<CompletedConversation.Message> messages = json.readValue(job.messagesJson(), MESSAGES);
            success = service.reflect(new CompletedConversation(job.ownerId(), job.conversationId(),
                    messages, job.observedAt()));
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("memory_reflection_queue worker_failed reason_type={}", exception.getClass().getSimpleName());
        }
        try {
            String status = success ? "COMPLETED" : job.attempts() >= MAX_ATTEMPTS ? "FAILED" : "QUEUED";
            Instant available = Instant.now().plusSeconds(success ? 0 : 30L * job.attempts());
            jdbc.update("""
                    UPDATE minikun_reflection_job
                    SET status = ?, available_at = ?, lease_until = NULL,
                        messages_json = CASE WHEN ? = 'QUEUED' THEN messages_json ELSE '' END,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'RUNNING' AND attempts = ?
                    """, status, Timestamp.from(available), status, job.id(), job.attempts());
            count(success ? "completed" : "FAILED".equals(status) ? "failed" : "retried");
        } catch (RuntimeException exception) {
            log.warn("memory_reflection_queue acknowledge_failed reason_type={}", exception.getClass().getSimpleName());
            count("acknowledge_failed");
        } finally {
            busy.set(false);
        }
    }

    public Map<String, Long> counts(String ownerId) {
        if (jdbc == null) return Map.of();
        return jdbc.query("""
                SELECT status, COUNT(*) AS total FROM minikun_reflection_job
                WHERE owner_id = ? GROUP BY status
                """, rs -> {
            Map<String, Long> values = new java.util.TreeMap<>();
            while (rs.next()) values.put(rs.getString("status"), rs.getLong("total"));
            return Map.copyOf(values);
        }, ownerId);
    }

    private void count(String event) {
        try {
            if (metrics != null) metrics.counter("minikun.memory.reflection.queue", "event", event).increment();
        } catch (RuntimeException ignored) { }
    }

    @Override public void close() {
        executor.shutdown();
        try { executor.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }

    private record Job(UUID id, String ownerId, String conversationId, String messagesJson,
            Instant observedAt, int attempts) {}
}
