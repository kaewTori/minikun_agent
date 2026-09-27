package com.minikun.memory.internal;

import static org.junit.jupiter.api.Assertions.*;
import com.minikun.memory.LongTermMemoryScope;
import com.minikun.memory.model.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Connection-local table shadows production; a new repository verifies durable reload. */
@EnabledIfEnvironmentVariable(named = "MINIKUN_EVAL_JDBC_URL", matches = ".+")
class TemporalMemoryIntegrationTest {
    @Test
    void keepsTimelineWhenCorrectionsArriveOutOfOrderAndPreferencesReturn() throws Exception {
        try (var source = new SingleConnectionDataSource(System.getenv("MINIKUN_EVAL_JDBC_URL"),
                System.getenv().getOrDefault("MINIKUN_EVAL_JDBC_USER", "minikun"),
                System.getenv().getOrDefault("MINIKUN_EVAL_JDBC_PASSWORD", ""), true)) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                CREATE TEMP TABLE minikun_memory (id uuid PRIMARY KEY, owner_id text, conversation_id text,
                category text, source text, content text, created_at timestamptz, confidence float8,
                reason text, fingerprint text UNIQUE, fact_subject text, fact_key text, fact_value text,
                valid_from timestamptz, valid_to timestamptz, stated_valid_to timestamptz, recorded_at timestamptz,
                evidence text, supersedes_id uuid)
                """);
            var repo = new JdbcMemoryRepository(jdbc);
            var latest = fact("owner", "ไม่ชอบหวาน", "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z");
            assertTrue(repo.save(latest));
            assertFalse(repo.save(latest)); // retry is idempotent
            assertTrue(repo.save(fact("owner", "ชอบหวาน", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z")));
            assertEquals("ไม่ชอบหวาน", current(repo, "owner").fact().value());
            var history = repo.findLongTerm(new LongTermMemoryScope("owner"), "เมื่อก่อนชอบหวานไหม", 10);
            assertEquals(2, history.size());
            assertEquals(Instant.parse("2026-03-01T00:00:00Z"), history.stream()
                .filter(m -> m.fact().value().equals("ชอบหวาน")).findFirst().orElseThrow().fact().validTo());
            assertNotNull(current(repo, "owner").supersedesId());
            // Repeating the original value later is a new version, not a fingerprint duplicate.
            repo.save(fact("owner", "ชอบหวาน", "2026-04-01T00:00:00Z", "2026-04-01T00:00:00Z"));
            repo.save(fact("other", "ความลับ", "2026-05-01T00:00:00Z", "2026-05-01T00:00:00Z"));
            var reloaded = new JdbcMemoryRepository(jdbc);
            assertEquals("ชอบหวาน", current(reloaded, "owner").fact().value());
            assertEquals(3, reloaded.findByOwner("owner", 20).size());
            assertEquals("ความลับ", current(reloaded, "other").fact().value());
            assertTrue(reloaded.findLongTerm(new LongTermMemoryScope("missing"), "' OR 1=1 --", 10).isEmpty());
            // Same event time: a later correction wins; earlier version has an empty validity interval.
            repo.save(fact("owner", "ไม่ชอบหวาน", "2026-04-01T00:00:00Z", "2026-04-02T00:00:00Z"));
            assertEquals("ไม่ชอบหวาน", current(repo, "owner").fact().value());
            assertEquals(4, repo.findByOwner("owner", 20).size());
            // Owner-authorized edit becomes a version; old evidence is retained.
            var current = current(repo, "owner");
            assertFalse(repo.updateByOwner("other", current.id(), new MemoryUpdate(MemoryCategory.PREFERENCE, "edited", 1, "owner correction")));
            assertTrue(repo.updateByOwner("owner", current.id(), new MemoryUpdate(MemoryCategory.PREFERENCE, "edited", 1, "owner correction")));
            assertEquals("edited", current(repo, "owner").fact().value());
            assertEquals(MemorySource.USER_DIRECTIVE, current(repo, "owner").source());
            assertEquals(5, repo.findByOwner("owner", 20).size());
            repo.deleteAllByOwner("owner");
            assertEquals("ความลับ", current(repo, "other").fact().value());
        }
    }
    private Memory current(JdbcMemoryRepository repo, String owner) {
        List<Memory> rows = repo.findLongTerm(new LongTermMemoryScope(owner), "หวาน", 10);
        assertEquals(1, rows.size());
        return rows.getFirst();
    }
    private AcceptedMemory fact(String owner, String value, String from, String recorded) {
        return new AcceptedMemory(owner, "chat-" + from, MemoryCategory.PREFERENCE, value, .95, "user said so",
            MemorySource.LLM_EXTRACTION, new TemporalFact("user", "food.sweet", value, Instant.parse(from), null,
                Instant.parse(recorded), value));
    }
}
