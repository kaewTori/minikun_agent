package com.minikun.memory.internal;

import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import lombok.extern.slf4j.Slf4j;

import com.minikun.memory.MemoryRepository;
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
    public boolean save(Memory memory, String fingerprint, String conversationId) {
        int updated = jdbcTemplate.update("""
                INSERT INTO minikun_memory
                    (id, conversation_id, category, source, content, created_at, fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (fingerprint) DO NOTHING
                """,
                memory.id().value(), conversationId, memory.category().name(), memory.source().name(),
                memory.content(), memory.createdAt().atOffset(ZoneOffset.UTC), fingerprint);
        log.debug("memory_repository_save conversation_id={} memory_id={} inserted={}",
                conversationId, memory.id().value(), updated);
        return updated > 0;
    }

    @Override
    public List<Memory> findAll() {
        return jdbcTemplate.query("""
                SELECT id, category, source, content, created_at
                FROM minikun_memory
                """, (resultSet, rowNumber) -> new Memory(
                new MemoryId(resultSet.getObject("id", java.util.UUID.class)),
                MemoryCategory.valueOf(resultSet.getString("category")),
                MemorySource.valueOf(resultSet.getString("source")),
                resultSet.getString("content"),
                resultSet.getTimestamp("created_at").toInstant()));
    }
}
