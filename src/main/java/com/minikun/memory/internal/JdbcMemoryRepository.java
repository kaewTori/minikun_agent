package com.minikun.memory.internal;

import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import lombok.extern.slf4j.Slf4j;

import com.minikun.memory.MemoryRepository;
import com.minikun.memory.model.AcceptedMemory;
import com.minikun.memory.model.Memory;
import com.minikun.memory.model.MemoryCategory;
import com.minikun.memory.model.MemoryId;
import com.minikun.memory.model.MemorySource;

@Slf4j
final class JdbcMemoryRepository implements MemoryRepository {
    private final JdbcTemplate jdbcTemplate;

    JdbcMemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean save(AcceptedMemory memory) {
        return insert(memory, fingerprint(memory), memory.conversationId());
    }

    @Override
    public boolean persist(AcceptedMemory memory) {
        return save(memory);
    }

    private boolean insert(AcceptedMemory memory, String fingerprint, String conversationId) {
        int updated = jdbcTemplate.update("""
                INSERT INTO minikun_memory
                    (id, conversation_id, category, source, content, created_at, confidence, reason, fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (fingerprint) DO NOTHING
                """,
                java.util.UUID.randomUUID(), conversationId, memory.category().name(), memory.source().name(),
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
    public List<Memory> findAll() {
        return jdbcTemplate.query("""
                SELECT id, category, source, content, created_at, confidence, reason
                FROM minikun_memory
                """, (resultSet, rowNumber) -> new Memory(
                new MemoryId(resultSet.getObject("id", java.util.UUID.class)),
                MemoryCategory.valueOf(resultSet.getString("category")),
                MemorySource.valueOf(resultSet.getString("source")),
                resultSet.getString("content"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getDouble("confidence"),
                resultSet.getString("reason")));
    }
}
