package com.minikun.knowledge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcPersonalKnowledgeRepository implements PersonalKnowledgeRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcPersonalKnowledgeRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc template must not be null");
        this.transactions = java.util.Objects.requireNonNull(transactions, "transaction template must not be null");
    }

    @Override
    public Optional<KnowledgeSourceRecord> find(String ownerId, String root, String path) {
        return jdbc.query("""
                SELECT id, owner_id, root_name, relative_path, display_name, status, file_hash,
                       modified_at, indexed_at, chunk_count, last_error
                FROM minikun_knowledge_source
                WHERE owner_id = ? AND root_name = ? AND relative_path = ?
                """, this::source, ownerId, root, path).stream().findFirst();
    }

    @Override
    public boolean embeddingsCurrent(UUID sourceId, String model) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT COUNT(*) > 0 AND bool_and(embedding <> '' AND embedding_model = ?)
                FROM minikun_knowledge_chunk WHERE source_id = ?
                """, Boolean.class, model, sourceId));
    }

    @Override
    public UUID begin(String ownerId, String root, String path, String name) {
        return jdbc.queryForObject("""
                INSERT INTO minikun_knowledge_source
                    (id, owner_id, root_name, relative_path, display_name, status)
                VALUES (?, ?, ?, ?, ?, 'INDEXING')
                ON CONFLICT (owner_id, root_name, relative_path) DO UPDATE
                SET display_name = EXCLUDED.display_name, status = 'INDEXING', last_error = ''
                RETURNING id
                """, UUID.class, UUID.randomUUID(), ownerId, root, path, name);
    }

    @Override
    public void replace(UUID sourceId, String hash, Instant modifiedAt, Instant indexedAt,
            List<KnowledgeChunkDraft> chunks) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM minikun_knowledge_chunk WHERE source_id = ?", sourceId);
            for (KnowledgeChunkDraft chunk : chunks) {
                jdbc.update("""
                        INSERT INTO minikun_knowledge_chunk
                            (id, source_id, chunk_index, heading, content, content_hash,
                             embedding, embedding_model, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), sourceId, chunk.index(), chunk.heading(), chunk.content(),
                        chunk.contentHash(), chunk.embedding(), chunk.embeddingModel(),
                        Timestamp.from(indexedAt));
            }
            jdbc.update("""
                    UPDATE minikun_knowledge_source
                    SET status = 'READY', file_hash = ?, modified_at = ?, indexed_at = ?,
                        chunk_count = ?, last_error = ''
                    WHERE id = ?
                    """, hash, Timestamp.from(modifiedAt), Timestamp.from(indexedAt), chunks.size(), sourceId);
        });
    }

    @Override
    public void fail(UUID sourceId, Instant indexedAt, String error) {
        jdbc.update("""
                UPDATE minikun_knowledge_source
                SET status = 'ERROR', indexed_at = ?, last_error = ?
                WHERE id = ?
                """, Timestamp.from(indexedAt), safeError(error), sourceId);
    }

    @Override
    public List<KnowledgeSourceRecord> list(String ownerId, int limit) {
        return jdbc.query("""
                SELECT id, owner_id, root_name, relative_path, display_name, status, file_hash,
                       modified_at, indexed_at, chunk_count, last_error
                FROM minikun_knowledge_source
                WHERE owner_id = ?
                ORDER BY relative_path, id
                LIMIT ?
                """, this::source, ownerId, limit);
    }

    @Override
    public List<KnowledgeChunk> chunks(String ownerId, int limit) {
        return jdbc.query("""
                SELECT c.id, c.source_id, s.root_name, s.relative_path, s.display_name,
                       c.chunk_index, c.heading, c.content, c.embedding, c.embedding_model
                FROM minikun_knowledge_chunk c
                JOIN minikun_knowledge_source s ON s.id = c.source_id
                WHERE s.owner_id = ? AND s.status IN ('READY', 'ERROR')
                ORDER BY s.indexed_at DESC NULLS LAST, s.id, c.chunk_index
                LIMIT ?
                """, (rs, row) -> new KnowledgeChunk(
                rs.getObject("id", UUID.class), rs.getObject("source_id", UUID.class),
                rs.getString("root_name"), rs.getString("relative_path"), rs.getString("display_name"),
                rs.getInt("chunk_index"), rs.getString("heading"), rs.getString("content"),
                rs.getString("embedding"), rs.getString("embedding_model")), ownerId, limit);
    }

    @Override
    public boolean delete(String ownerId, UUID sourceId) {
        return jdbc.update("DELETE FROM minikun_knowledge_source WHERE owner_id = ? AND id = ?",
                ownerId, sourceId) > 0;
    }

    @Override
    public int sourceCount(String ownerId) {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM minikun_knowledge_source WHERE owner_id = ?", Integer.class, ownerId);
        return value == null ? 0 : value;
    }

    @Override
    public int chunkCount(String ownerId) {
        Integer value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM minikun_knowledge_chunk c
                JOIN minikun_knowledge_source s ON s.id = c.source_id
                WHERE s.owner_id = ?
                """, Integer.class, ownerId);
        return value == null ? 0 : value;
    }

    private KnowledgeSourceRecord source(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Timestamp modified = rs.getTimestamp("modified_at");
        Timestamp indexed = rs.getTimestamp("indexed_at");
        return new KnowledgeSourceRecord(
                rs.getObject("id", UUID.class), rs.getString("owner_id"), rs.getString("root_name"),
                rs.getString("relative_path"), rs.getString("display_name"), rs.getString("status"),
                rs.getString("file_hash"), modified == null ? null : modified.toInstant(),
                indexed == null ? null : indexed.toInstant(), rs.getInt("chunk_count"),
                rs.getString("last_error"));
    }

    private String safeError(String value) {
        String result = value == null || value.isBlank() ? "indexing failed" : value.replaceAll("[\\r\\n]+", " ");
        return result.length() <= 500 ? result : result.substring(0, 500);
    }
}
