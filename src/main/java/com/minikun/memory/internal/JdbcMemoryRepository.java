package com.minikun.memory.internal;

import org.springframework.jdbc.core.JdbcTemplate;

import com.minikun.memory.MemoryRepository;
import com.minikun.memory.model.Memory;

final class JdbcMemoryRepository implements MemoryRepository {
    private final JdbcTemplate jdbcTemplate;

    JdbcMemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean save(Memory memory, String fingerprint, String conversationId) {
        return jdbcTemplate.update("""
                INSERT INTO minikun_memory
                    (id, conversation_id, category, source, content, created_at, fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (fingerprint) DO NOTHING
                """,
                memory.id().value(), conversationId, memory.category().name(), memory.source().name(),
                memory.content(), memory.createdAt(), fingerprint) > 0;
    }
}
