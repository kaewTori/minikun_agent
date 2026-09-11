package com.minikun.memory.internal;

import static org.junit.jupiter.api.Assertions.*;
import com.minikun.memory.LongTermMemoryScope;
import com.minikun.memory.model.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Requires a disposable database; every table is contained in a unique, finally-deleted schema. */
@EnabledIfEnvironmentVariable(named = "MINIKUN_EVAL_DISPOSABLE_DB", matches = "true")
class TemporalMemoryConcurrencyTest {
    @Test
    void concurrentWritersSerializeAndFailedTimelineUpdateRollsBackTheInsert() throws Exception {
        String url = System.getenv("MINIKUN_EVAL_JDBC_URL");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", ""));
        String schema = "eval_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            admin.execute("CREATE TABLE " + schema + ".minikun_memory (LIKE public.minikun_memory INCLUDING ALL)");
            String isolatedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
            var isolated = new DriverManagerDataSource(isolatedUrl, "postgres", "");
            try (var pool = Executors.newFixedThreadPool(6)) {
                var ready = new CountDownLatch(6);
                var futures = new java.util.ArrayList<Future<Boolean>>();
                for (int day = 1; day <= 6; day++) {
                    int version = day;
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        assertTrue(ready.await(10, TimeUnit.SECONDS));
                        return new JdbcMemoryRepository(new JdbcTemplate(isolated)).save(fact("owner", "value-" + version, version));
                    }));
                }
                for (var future : futures) assertTrue(future.get(20, TimeUnit.SECONDS));
            }
            // A completely new connection and repository must see exactly the newest fact.
            var jdbc = new JdbcTemplate(isolated);
            var repo = new JdbcMemoryRepository(jdbc);
            var current = repo.findLongTerm(new LongTermMemoryScope("owner"), 20);
            assertEquals(1, current.size());
            assertEquals("value-6", current.getFirst().fact().value());
            assertEquals(6, repo.findByOwner("owner", 20).size());
            jdbc.execute("ALTER TABLE minikun_memory ADD CONSTRAINT rollback_probe CHECK (owner_id <> 'rollback' OR valid_to IS NULL)");
            repo.save(fact("rollback", "first", 1));
            assertThrows(RuntimeException.class, () -> repo.save(fact("rollback", "second", 2)));
            assertEquals(1, repo.findByOwner("rollback", 10).size(), "insert must roll back when closing old fact fails");
            assertEquals("first", repo.findLongTerm(new LongTermMemoryScope("rollback"), 10).getFirst().fact().value());
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
    private AcceptedMemory fact(String owner, String value, int day) {
        Instant time = Instant.parse("2026-01-0" + day + "T00:00:00Z");
        return new AcceptedMemory(owner, "chat-" + day, MemoryCategory.PREFERENCE, value, .95, "synthetic evidence",
            MemorySource.LLM_EXTRACTION, new TemporalFact("user", "test.preference", value, time, null, time, value));
    }
}
