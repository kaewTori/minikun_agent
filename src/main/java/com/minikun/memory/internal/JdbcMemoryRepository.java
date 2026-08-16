package com.minikun.memory.internal;

import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import lombok.extern.slf4j.Slf4j;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import com.minikun.memory.MemoryRepository;
import com.minikun.memory.MemoryScope;
import com.minikun.memory.model.AcceptedMemory;
import com.minikun.memory.model.Memory;
import com.minikun.memory.model.MemoryCategory;
import com.minikun.memory.model.MemoryId;
import com.minikun.memory.model.MemorySource;

@Slf4j
final class JdbcMemoryRepository implements MemoryRepository {
    private static final String PERSIST_SUCCESS = "minikun.memory.reflection.persist.success";
    private static final String PERSIST_CONFLICT = "minikun.memory.reflection.persist.conflict";
    private static final String PERSIST_FAILURE = "minikun.memory.reflection.persist.failure";
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    JdbcMemoryRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null);
    }

    JdbcMemoryRepository(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean save(AcceptedMemory memory) {
        if (memory.ownerId() == null) {
            throw new IllegalArgumentException("owner id is required for persisted memory");
        }
        try {
            boolean inserted = insert(memory, fingerprint(memory), memory.conversationId());
            increment(inserted ? PERSIST_SUCCESS : PERSIST_CONFLICT);
            return inserted;
        } catch (RuntimeException exception) {
            increment(PERSIST_FAILURE);
            throw exception;
        }
    }

    private void increment(String name) {
        try {
            Counter.builder(name).register(meterRegistry).increment();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public boolean persist(AcceptedMemory memory) {
        return save(memory);
    }

    private boolean insert(AcceptedMemory memory, String fingerprint, String conversationId) {
        int updated = jdbcTemplate.update("""
                INSERT INTO minikun_memory
                    (id, owner_id, conversation_id, category, source, content, created_at, confidence, reason, fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (fingerprint) DO NOTHING
                """,
                java.util.UUID.randomUUID(), memory.ownerId(), conversationId, memory.category().name(), memory.source().name(),
                memory.content(), java.time.Instant.now().atOffset(ZoneOffset.UTC), memory.confidence(),
                memory.reason(), fingerprint);
        log.debug("memory_repository_save conversation_id={} inserted={}", conversationId, updated);
        return updated > 0;
    }

    private String fingerprint(AcceptedMemory memory) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(
                            (memory.conversationId() + "\u0000" + memory.category() + "\u0000"
                                    + memory.content().trim().replaceAll("\\s+", " ")
                                    .toLowerCase(java.util.Locale.ROOT))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public List<Memory> find(MemoryScope scope, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("memory retrieval limit must not be negative");
        }
        List<Memory> memories = jdbcTemplate.query("""
                SELECT owner_id, conversation_id, id, category, source, content, created_at, confidence, reason
                FROM minikun_memory
            WHERE owner_id = ? OR owner_id IS NULL
                ORDER BY created_at DESC, id
                LIMIT ?
                """, (resultSet, rowNumber) -> new Memory(
                resultSet.getString("owner_id"),
                resultSet.getString("conversation_id"),
                new MemoryId(resultSet.getObject("id", java.util.UUID.class)),
                MemoryCategory.valueOf(resultSet.getString("category")),
                MemorySource.valueOf(resultSet.getString("source")),
                resultSet.getString("content"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getDouble("confidence"),
                resultSet.getString("reason")),
                scope.ownerId(), limit);
            log.info("memory_repository_find owner_id={} conversation_id={} rows={}",
                scope.ownerId(), scope.conversationId().value(), memories.size());
            return memories;
    }

    @Override
    public List<Memory> findByOwner(String ownerId, int limit) {
        validateOwner(ownerId);
        validateLimit(limit);
        return jdbcTemplate.query("""
                SELECT owner_id, conversation_id, id, category, source, content, created_at, confidence, reason
                FROM minikun_memory
                WHERE owner_id = ?
                ORDER BY created_at DESC, id
                LIMIT ?
                """, (resultSet, rowNumber) -> new Memory(
                resultSet.getString("owner_id"),
                resultSet.getString("conversation_id"),
                new MemoryId(resultSet.getObject("id", java.util.UUID.class)),
                MemoryCategory.valueOf(resultSet.getString("category")),
                MemorySource.valueOf(resultSet.getString("source")),
                resultSet.getString("content"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getDouble("confidence"),
                resultSet.getString("reason")), ownerId, limit);
    }

    @Override
    public boolean deleteByOwner(String ownerId, MemoryId memoryId) {
        validateOwner(ownerId);
        java.util.Objects.requireNonNull(memoryId, "memory id must not be null");
        return jdbcTemplate.update(
                "DELETE FROM minikun_memory WHERE owner_id = ? AND id = ?",
                ownerId, memoryId.value()) > 0;
    }

    @Override
    public int deleteAllByOwner(String ownerId) {
        validateOwner(ownerId);
        return jdbcTemplate.update("DELETE FROM minikun_memory WHERE owner_id = ?", ownerId);
    }

    private void validateOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank() || "*".equals(ownerId)) {
            throw new IllegalArgumentException("owner id must not be blank or wildcard");
        }
    }

    private void validateLimit(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("memory listing limit must not be negative");
        }
    }
}
