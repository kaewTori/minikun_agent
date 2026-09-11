package com.minikun.memory.internal;

import static org.junit.jupiter.api.Assertions.*;
import com.minikun.memory.LongTermMemoryScope;
import com.minikun.memory.MemoryRecallService;
import com.minikun.memory.MemoryRelevanceRanker;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

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
}
