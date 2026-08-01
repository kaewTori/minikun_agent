package com.minikun.memory.internal;

import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;

import lombok.extern.slf4j.Slf4j;

import com.minikun.memory.MemoryRepository;
import com.minikun.memory.model.Memory;

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
}
