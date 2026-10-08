package com.minikun.memory.internal;

import static org.junit.jupiter.api.Assertions.*;
import com.minikun.memory.LongTermMemoryScope;
import com.minikun.memory.MemoryRecallService;
import com.minikun.memory.MemoryRelevanceRanker;
import com.minikun.memory.EmbeddingMemoryRelevanceRanker;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.anyList;

/** Uses only a connection-local temporary table, never the application's real memory table. */
@EnabledIfEnvironmentVariable(named = "MINIKUN_EVAL_JDBC_URL", matches = ".+")
class JdbcMemoryRecallIntegrationTest {
    @Test
    void retrievesOldThaiMemoryAcrossWholeOwnerCorpusAndPreservesItsRank() {
        try (var source = new SingleConnectionDataSource(System.getenv("MINIKUN_EVAL_JDBC_URL"),
                System.getenv().getOrDefault("MINIKUN_EVAL_JDBC_USER", "minikun"),
                System.getenv().getOrDefault("MINIKUN_EVAL_JDBC_PASSWORD", ""), true)) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    CREATE TEMP TABLE minikun_memory (
                        owner_id text, conversation_id text, id uuid, category text, source text,
                        content text, created_at timestamptz, confidence double precision, reason text,
                        fact_subject text, fact_key text, fact_value text, valid_from timestamptz,
                        valid_to timestamptz, recorded_at timestamptz, evidence text, supersedes_id uuid)
                    """);
            jdbc.update("""
                    INSERT INTO minikun_memory (owner_id, conversation_id, id, category, source, content, created_at, confidence, reason)
                    SELECT 'owner', 'chat', md5(n::text)::uuid, 'PROFILE', 'USER_DIRECTIVE',
                        'บันทึกประจำวัน ' || n, now(), 0.95, 'test fixture' FROM generate_series(1,100) n
                    """);
            UUID old = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO minikun_memory (owner_id, conversation_id, id, category, source, content, created_at, confidence, reason) VALUES
                    ('owner', 'old-chat', ?, 'PROFILE', 'USER_DIRECTIVE', 'แมวของฉันชื่อมะลิ', now() - interval '1 year', .95, 'test fixture'),
                    ('another-owner', 'private', ?, 'PROFILE', 'USER_DIRECTIVE', 'แมวของฉันชื่อความลับ', now(), 1, 'test fixture')
                    """, old, UUID.randomUUID());
            var repository = new JdbcMemoryRepository(jdbc);
            var candidates = repository.findLongTerm(new LongTermMemoryScope("owner"), "แมวของฉันชื่ออะไร", 10);
            assertTrue(candidates.stream().anyMatch(memory -> memory.id().value().equals(old)));
            assertTrue(candidates.stream().allMatch(memory -> memory.ownerId().equals("owner")));
            var recall = new MemoryRecallService(repository,
                    memories -> new MemoryFormatter(4000).format(new MemorySelector(1).select(memories)),
                    MemoryRelevanceRanker::rank);
            assertTrue(recall.recall(new LongTermMemoryScope("owner"), "แมวของฉันชื่ออะไร", 1).content().contains("มะลิ"));
            assertEquals(10, repository.findLongTerm(new LongTermMemoryScope("owner"), "", 10).size());
            assertTrue(repository.findLongTerm(new LongTermMemoryScope("nobody"), "แมว", 10).isEmpty());
            // SQL punctuation must stay a bound value; it cannot widen owner scope.
            assertTrue(repository.findLongTerm(new LongTermMemoryScope("nobody"), "' OR 1=1 --", 10).isEmpty());
        }
    }

    @Test
    void semanticRecallFindsOldMemoryWithoutMatchingQueryWords() {
        try (var source = new SingleConnectionDataSource(System.getenv("MINIKUN_EVAL_JDBC_URL"),
                System.getenv().getOrDefault("MINIKUN_EVAL_JDBC_USER", "minikun"),
                System.getenv().getOrDefault("MINIKUN_EVAL_JDBC_PASSWORD", ""), true)) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    CREATE TEMP TABLE minikun_memory (
                        owner_id text, conversation_id text, id uuid, category text, source text,
                        content text, created_at timestamptz, confidence double precision, reason text,
                        fact_subject text, fact_key text, fact_value text, valid_from timestamptz,
                        valid_to timestamptz, recorded_at timestamptz, evidence text, supersedes_id uuid)
                    """);
            jdbc.update("""
                    INSERT INTO minikun_memory (owner_id, conversation_id, id, category, source, content, created_at, confidence, reason)
                    SELECT 'owner', 'recent', md5(n::text)::uuid, 'PROFILE', 'LLM_EXTRACTION',
                        'บันทึกประจำวัน ' || n, now(), .8, 'fixture' FROM generate_series(1,100) n
                    """);
            var oldId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO minikun_memory (owner_id, conversation_id, id, category, source, content, created_at, confidence, reason)
                    VALUES ('owner', 'old', ?, 'PREFERENCE', 'LLM_EXTRACTION', 'ชอบเดินทางด้วยรถไฟ', now() - interval '1 year', .8, 'fixture')
                    """, oldId);
            var model = mock(EmbeddingModel.class);
            when(model.embed("การคมนาคมที่พี่โปรด")).thenReturn(new float[] {1, 0});
            when(model.embed(org.mockito.ArgumentMatchers.<java.util.List<String>>any())).thenAnswer(invocation ->
                    ((java.util.List<String>) invocation.getArgument(0)).stream()
                            .map(text -> text.contains("รถไฟ") ? new float[] {1, 0} : new float[] {0, 1}).toList());
            var recall = new MemoryRecallService(new JdbcMemoryRepository(jdbc),
                    memories -> new MemoryFormatter(4000).format(new MemorySelector(1).select(memories)),
                    new EmbeddingMemoryRelevanceRanker(model, .9, new SimpleMeterRegistry()));
            assertTrue(recall.recall(new LongTermMemoryScope("owner"), "การคมนาคมที่พี่โปรด", 1)
                    .content().contains("รถไฟ"));
        }
    }

    @Test
    void storedVectorsFindOldMemoryBeyondLexicalPoolWithoutReembeddingDocuments() {
        try (var source = new SingleConnectionDataSource(System.getenv("MINIKUN_EVAL_JDBC_URL"),
                System.getenv().getOrDefault("MINIKUN_EVAL_JDBC_USER", "minikun"),
                System.getenv().getOrDefault("MINIKUN_EVAL_JDBC_PASSWORD", ""), true)) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    CREATE TEMP TABLE minikun_memory (
                        owner_id text, conversation_id text, id uuid, category text, source text,
                        content text, created_at timestamptz, confidence double precision, reason text,
                        fact_subject text, fact_key text, fact_value text, valid_from timestamptz,
                        valid_to timestamptz, recorded_at timestamptz, evidence text, supersedes_id uuid,
                        embedding vector, embedding_model text)
                    """);
            jdbc.update("""
                    INSERT INTO minikun_memory (owner_id, conversation_id, id, category, source, content, created_at, confidence, reason)
                    SELECT 'owner', 'recent', md5(n::text)::uuid, 'PROFILE', 'LLM_EXTRACTION',
                        'บันทึกประจำวัน ' || n, now(), .8, 'fixture' FROM generate_series(1,300) n
                    """);
            UUID oldId = UUID.randomUUID();
            UUID privateId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO minikun_memory (owner_id, conversation_id, id, category, source, content, created_at, confidence, reason) VALUES
                    ('owner', 'old', ?, 'PREFERENCE', 'LLM_EXTRACTION', 'ชอบเดินทางด้วยรถไฟ', now() - interval '1 year', .8, 'fixture'),
                    ('other', 'private', ?, 'PREFERENCE', 'USER_DIRECTIVE', 'ชอบเดินทางด้วยรถไฟ', now(), 1, 'fixture')
                    """, oldId, privateId);
            jdbc.update("UPDATE minikun_memory SET embedding = '[1,0]'::vector, embedding_model = 'embeddinggemma-2:270m-mxfp8-text' WHERE id = ?", privateId);
            var model = mock(EmbeddingModel.class);
            when(model.embed("task: search result | query: การคมนาคมที่พี่โปรด")).thenReturn(new float[] {1, 0});
            when(model.embed(org.mockito.ArgumentMatchers.<java.util.List<String>>any())).thenAnswer(invocation ->
                    ((java.util.List<String>) invocation.getArgument(0)).stream()
                            .map(text -> text.contains("รถไฟ") ? new float[] {1, 0} : new float[] {0, 1}).toList());
            var repository = new JdbcMemoryRepository(jdbc, new SimpleMeterRegistry(), model, "embeddinggemma-2:270m-mxfp8-text", .85);
            repository.backfillEmbeddings();
            assertTrue(jdbc.queryForObject("SELECT embedding IS NOT NULL FROM minikun_memory WHERE id = ?", Boolean.class, oldId));
            var matches = repository.findLongTerm(new LongTermMemoryScope("owner"), "การคมนาคมที่พี่โปรด", 200);
            assertEquals(oldId, matches.getFirst().id().value());
            assertTrue(matches.stream().allMatch(memory -> memory.ownerId().equals("owner")));
            verify(model, times(1)).embed(anyList());
            verify(model, times(1)).embed("task: search result | query: การคมนาคมที่พี่โปรด");
            when(model.embed("task: search result | query: รถไฟ")).thenThrow(new IllegalStateException("temporary embedding outage"));
            assertEquals(oldId, repository.findLongTerm(new LongTermMemoryScope("owner"), "รถไฟ", 200).getFirst().id().value());
        }
    }
}
