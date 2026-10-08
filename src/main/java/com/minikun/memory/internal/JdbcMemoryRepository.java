package com.minikun.memory.internal;

import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.embedding.EmbeddingModel;
import com.minikun.knowledge.EmbeddingSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

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
import com.minikun.memory.model.MemoryUpdate;

@Slf4j
final class JdbcMemoryRepository implements MemoryRepository {
    private static final String PERSIST_SUCCESS = "minikun.memory.reflection.persist.success";
    private static final String PERSIST_CONFLICT = "minikun.memory.reflection.persist.conflict";
    private static final String PERSIST_FAILURE = "minikun.memory.reflection.persist.failure";
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final EmbeddingModel embeddingModel;
    private final String embeddingModelName;
    private final double semanticWeight;
    private static final String CURRENT = " AND (fact_key IS NULL OR (COALESCE(valid_from, recorded_at) <= CURRENT_TIMESTAMP AND (valid_to IS NULL OR valid_to > CURRENT_TIMESTAMP)))";


    JdbcMemoryRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null);
    }

    JdbcMemoryRepository(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this(jdbcTemplate, meterRegistry, null, "", 0.85);
    }

    JdbcMemoryRepository(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry,
            EmbeddingModel embeddingModel, String embeddingModelName, double semanticWeight) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        this.embeddingModel = embeddingModel;
        this.embeddingModelName = embeddingModelName;
        if (!Double.isFinite(semanticWeight) || semanticWeight < 0 || semanticWeight > 1)
            throw new IllegalArgumentException("semantic memory weight must be between 0 and 1");
        this.semanticWeight = semanticWeight;
    }

    boolean semanticEnabled() {
        return embeddingModel != null;
    }

    @Scheduled(initialDelayString = "${minikun.memory.semantic.backfill-initial-delay:10000}",
            fixedDelayString = "${minikun.memory.semantic.backfill-delay:30000}")
    void backfillEmbeddings() {
        if (embeddingModel == null) return;
        try {
            var rows = jdbcTemplate.query("""
                    SELECT id, category, content FROM minikun_memory
                    WHERE owner_id IS NOT NULL AND owner_id <> '' AND owner_id <> '*'
                      AND (embedding IS NULL OR embedding_model IS DISTINCT FROM ?)
                    ORDER BY created_at, id LIMIT 64
                    """, (rs, n) -> new EmbeddingRow(rs.getObject("id", java.util.UUID.class),
                            rs.getString("category"), rs.getString("content")), embeddingModelName);
            if (rows.isEmpty()) return;
            List<float[]> vectors = embeddingModel.embed(rows.stream()
                    .map(row -> EmbeddingSupport.gemma(embeddingModelName)
                            ? EmbeddingSupport.document(embeddingModelName, row.category(), row.content())
                            : row.category() + ": " + row.content()).toList());
            if (vectors == null || vectors.size() != rows.size()) throw new IllegalStateException("embedding batch size mismatch");
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                jdbcTemplate.update("""
                        UPDATE minikun_memory SET embedding = ?::vector, embedding_model = ?
                        WHERE id = ? AND category = ? AND content = ?
                        """, vector(vectors.get(i)), embeddingModelName, row.id(), row.category(), row.content());
            }
            log.info("memory_embedding_backfill rows={}", rows.size());
        } catch (RuntimeException exception) {
            log.warn("memory_embedding_backfill_failed error_type={}", exception.getClass().getSimpleName());
        }
    }

    private record EmbeddingRow(java.util.UUID id, String category, String content) {}

    private static String vector(float[] values) {
        return Arrays.toString(EmbeddingSupport.requireVector(values));
    }

    @Override
    public boolean save(AcceptedMemory memory) {
        if (memory.ownerId() == null) {
            throw new IllegalArgumentException("owner id is required for persisted memory");
        }
        try {
            boolean inserted = memory.fact() == null
                    ? insert(memory, fingerprint(memory), memory.conversationId()) : saveFact(memory);
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

    private boolean saveFact(AcceptedMemory memory) {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbcTemplate.getDataSource()));
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var fact = memory.fact();
            // Serialize each owner's slot, including simultaneous inserts when no row exists yet.
            jdbcTemplate.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    memory.ownerId().length() + ":" + memory.ownerId() + fact.subject().length() + ":" + fact.subject() + fact.key());
            int inserted = jdbcTemplate.update("""
                    INSERT INTO minikun_memory
                    (id, owner_id, conversation_id, category, source, content, created_at, confidence, reason,
                     fingerprint, fact_subject, fact_key, fact_value, valid_from, valid_to, stated_valid_to, recorded_at, evidence)
                    VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (fingerprint) DO NOTHING
                    """, java.util.UUID.randomUUID(), memory.ownerId(), memory.conversationId(), memory.category().name(),
                    memory.source().name(), memory.content(), memory.confidence(), memory.reason(), fingerprint(memory),
                    fact.subject(), fact.key(), fact.value(), timestamp(fact.validFrom()), timestamp(fact.validTo()),
                    timestamp(fact.validTo()), timestamp(fact.recordedAt()), fact.evidence());
            if (inserted == 0) return false;
            // ponytail: rebuild one slot's small timeline; optimize only if a single slot has thousands of versions.
            jdbcTemplate.update("""
                    WITH timeline AS (
                        SELECT id, lag(id) OVER w AS previous_id,
                            lead(COALESCE(valid_from, recorded_at)) OVER w AS next_start
                        FROM minikun_memory WHERE owner_id = ? AND fact_subject = ? AND fact_key = ?
                        WINDOW w AS (ORDER BY COALESCE(valid_from, recorded_at), recorded_at, id)
                    )
                    UPDATE minikun_memory m SET valid_to = LEAST(m.stated_valid_to, t.next_start),
                        supersedes_id = t.previous_id FROM timeline t WHERE m.id = t.id
                    """, memory.ownerId(), fact.subject(), fact.key());
            return true;
        }));
    }

    private static java.time.OffsetDateTime timestamp(java.time.Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static java.time.Instant instant(java.sql.ResultSet row, String name) throws java.sql.SQLException {
        var timestamp = row.getTimestamp(name);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static com.minikun.memory.model.TemporalFact fact(java.sql.ResultSet row) throws java.sql.SQLException {
        return row.getString("fact_key") == null ? null : new com.minikun.memory.model.TemporalFact(
                row.getString("fact_subject"), row.getString("fact_key"), row.getString("fact_value"),
                instant(row, "valid_from"), instant(row, "valid_to"), instant(row, "recorded_at"), row.getString("evidence"));
    }

    private String fingerprint(AcceptedMemory memory) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(
                            (memory.ownerId() + "\u0000" + memory.category() + "\u0000"
                                    + (memory.fact() == null ? "" : memory.fact().toString()) + "\u0000"
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
                SELECT owner_id, conversation_id, id, category, source, content, created_at, confidence, reason, fact_subject, fact_key, fact_value, valid_from, valid_to, recorded_at, evidence, supersedes_id
                FROM minikun_memory
            WHERE owner_id = ? AND (fact_key IS NULL OR (COALESCE(valid_from, recorded_at) <= CURRENT_TIMESTAMP AND (valid_to IS NULL OR valid_to > CURRENT_TIMESTAMP)))
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
                resultSet.getString("reason"), fact(resultSet), resultSet.getObject("supersedes_id", java.util.UUID.class)),
                scope.ownerId(), limit);
            log.info("memory_repository_find owner_id={} conversation_id={} rows={}",
                scope.ownerId(), scope.conversationId().value(), memories.size());
            return memories;
    }

    @Override
    public List<Memory> findByOwner(String ownerId, int limit) {
        validateOwner(ownerId);
        validateLimit(limit);
        return queryOwnerMemories("WHERE owner_id = ? ORDER BY created_at DESC, id LIMIT ?", ownerId, limit);
    }

    @Override
    public List<Memory> findLongTerm(com.minikun.memory.LongTermMemoryScope scope, int limit) {
        validateOwner(scope.ownerId());
        validateLimit(limit);
        return queryOwnerMemories("WHERE owner_id = ?" + CURRENT + " ORDER BY created_at DESC, id LIMIT ?", scope.ownerId(), limit);
    }

    @Override
    public List<Memory> findLongTerm(com.minikun.memory.LongTermMemoryScope scope, String query, int limit) {
        validateOwner(scope.ownerId());
        validateLimit(limit);
        if (limit == 0) return List.of();
        var terms = com.minikun.memory.MemoryRelevanceRanker.terms(query);
        boolean historical = com.minikun.memory.MemoryRecallService.historical(query);
        String filter = historical ? "" : CURRENT;
        var candidates = new java.util.LinkedHashMap<com.minikun.memory.model.MemoryId, Memory>();
        if (!terms.isEmpty()) {
            // ponytail: exact owner-filtered scan is sufficient at today's corpus size; add ANN only if profiling warrants it.
            String score = terms.stream().map(term -> "CASE WHEN strpos(lower(content), ?) > 0 THEN 1 ELSE 0 END")
                    .collect(java.util.stream.Collectors.joining(" + "));
            java.util.List<Object> arguments = new java.util.ArrayList<>();
            arguments.add(scope.ownerId());
            arguments.addAll(terms);
            arguments.add(Math.max(1, limit - limit / 5));
            queryOwnerMemories("WHERE owner_id = ?" + filter + " ORDER BY (" + score + ") DESC, confidence DESC, created_at DESC, id LIMIT ?",
                    arguments.toArray()).forEach(memory -> candidates.put(memory.id(), memory));
        }
        queryOwnerMemories("WHERE owner_id = ?" + filter + " ORDER BY created_at DESC, id LIMIT ?", scope.ownerId(),
                terms.isEmpty() ? limit : Math.max(1, limit / 5)).forEach(memory -> candidates.putIfAbsent(memory.id(), memory));
        if (embeddingModel == null || query == null || query.isBlank()) return candidates.values().stream().limit(limit).toList();
        try {
            float[] queryVector = embeddingModel.embed(EmbeddingSupport.query(embeddingModelName, query, ""));
            String vector = vector(queryVector);
            List<Memory> semantic = queryOwnerMemories("""
                    WHERE owner_id = ? AND embedding_model = ? AND vector_dims(embedding) = ?
                    """ + filter + " ORDER BY embedding <=> ?::vector, created_at DESC, id LIMIT ?",
                    scope.ownerId(), embeddingModelName, queryVector.length, vector, limit);
            if (semantic.isEmpty()) return candidates.values().stream().limit(limit).toList();
            Map<MemoryId, Double> scores = new HashMap<>();
            Map<MemoryId, Memory> merged = new HashMap<>();
            for (int i = 0; i < semantic.size(); i++) {
                Memory memory = semantic.get(i);
                merged.put(memory.id(), memory);
                scores.merge(memory.id(), semanticWeight / (i + 1), Double::sum);
            }
            int rank = 0;
            for (Memory memory : candidates.values()) {
                merged.putIfAbsent(memory.id(), memory);
                scores.merge(memory.id(), (1 - semanticWeight) / (++rank), Double::sum);
            }
            return merged.values().stream().sorted(Comparator
                    .comparingDouble((Memory memory) -> scores.get(memory.id())).reversed()
                    .thenComparing(Memory::createdAt, Comparator.reverseOrder()))
                    .limit(limit).toList();
        } catch (RuntimeException exception) {
            log.warn("memory_semantic_recall_fallback error_type={}", exception.getClass().getSimpleName());
            return candidates.values().stream().limit(limit).toList();
        }
    }

    private List<Memory> queryOwnerMemories(String suffix, Object... arguments) {
        return jdbcTemplate.query("""
                SELECT owner_id, conversation_id, id, category, source, content, created_at, confidence, reason, fact_subject, fact_key, fact_value, valid_from, valid_to, recorded_at, evidence, supersedes_id
                FROM minikun_memory
                """ + suffix, (resultSet, rowNumber) -> new Memory(
                resultSet.getString("owner_id"),
                resultSet.getString("conversation_id"),
                new MemoryId(resultSet.getObject("id", java.util.UUID.class)),
                MemoryCategory.valueOf(resultSet.getString("category")),
                MemorySource.valueOf(resultSet.getString("source")),
                resultSet.getString("content"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getDouble("confidence"),
                resultSet.getString("reason"), fact(resultSet), resultSet.getObject("supersedes_id", java.util.UUID.class)), arguments);
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
    public boolean updateByOwner(String ownerId, MemoryId memoryId, MemoryUpdate update) {
        validateOwner(ownerId);
        java.util.Objects.requireNonNull(memoryId, "memory id must not be null");
        java.util.Objects.requireNonNull(update, "memory update must not be null");
        List<Memory> existing = jdbcTemplate.query("""
                SELECT owner_id, conversation_id, id, category, source, content, created_at, confidence, reason, fact_subject, fact_key, fact_value, valid_from, valid_to, recorded_at, evidence, supersedes_id
                FROM minikun_memory WHERE owner_id = ? AND id = ?
                """, (resultSet, rowNumber) -> new Memory(
                resultSet.getString("owner_id"), resultSet.getString("conversation_id"),
                new MemoryId(resultSet.getObject("id", java.util.UUID.class)),
                MemoryCategory.valueOf(resultSet.getString("category")),
                MemorySource.valueOf(resultSet.getString("source")), resultSet.getString("content"),
                resultSet.getTimestamp("created_at").toInstant(), resultSet.getDouble("confidence"),
                resultSet.getString("reason"), fact(resultSet), resultSet.getObject("supersedes_id", java.util.UUID.class)), ownerId, memoryId.value());
        if (existing.isEmpty()) {
            return false;
        }
        Memory current = existing.getFirst();
        if (current.fact() != null) {
            var fact = current.fact();
            var now = java.time.Instant.now();
            return save(new AcceptedMemory(ownerId, current.conversationId(), update.category(), update.content(),
                    update.confidence(), update.reason(), com.minikun.memory.model.MemorySource.USER_DIRECTIVE,
                    new com.minikun.memory.model.TemporalFact(fact.subject(), fact.key(), update.content(), now, null, now, update.content())));
        }
        String fingerprint = fingerprint(new AcceptedMemory(ownerId, current.conversationId(), update.category(),
                update.content(), update.confidence(), update.reason(), current.source()));
        return jdbcTemplate.update("""
                UPDATE minikun_memory
                SET category = ?, content = ?, confidence = ?, reason = ?, fingerprint = ?,
                    embedding = NULL, embedding_model = NULL
                WHERE owner_id = ? AND id = ?
                """, update.category().name(), update.content(), update.confidence(), update.reason(), fingerprint,
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
