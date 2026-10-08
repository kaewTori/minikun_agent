package com.minikun.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.model.CompletedConversation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Runs against a disposable PostgreSQL database with an isolated, finally-deleted schema. */
@EnabledIfEnvironmentVariable(named = "MINIKUN_EVAL_DISPOSABLE_DB", matches = "true")
class DeferredReflectionQueueIntegrationTest {
    @Test
    void deduplicatesAndResumesExpiredLeaseWithoutKeepingCompletedChatText() throws Exception {
        String url = System.getenv("MINIKUN_EVAL_JDBC_URL");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", ""));
        String schema = "eval_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            String isolatedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
            var dataSource = new DriverManagerDataSource(isolatedUrl, "postgres", "");
            try (var connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                        Path.of("deploy/migrations/V20261006_01__durable_reflection_queue.sql")));
            }
            var jdbc = new JdbcTemplate(dataSource);
            ReflectionService reflection = mock(ReflectionService.class);
            when(reflection.reflect(org.mockito.ArgumentMatchers.any())).thenReturn(true);
            @SuppressWarnings("unchecked") ObjectProvider<ReflectionService> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(reflection);
            var conversation = new CompletedConversation("owner", "conversation", List.of(
                    new CompletedConversation.Message("user", "private test message"),
                    new CompletedConversation.Message("assistant", "response")), Instant.now());
            try (var queue = new DeferredReflectionService(jdbc, provider, new ObjectMapper(),
                    new SimpleMeterRegistry())) {
                assertTrue(queue.submit(reflection, conversation, "same-request"));
                assertTrue(queue.submit(reflection, conversation, "same-request"));
                assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM minikun_reflection_job", Long.class));
                queue.poll();
                awaitCompleted(jdbc, 1);
                verify(reflection, times(1)).reflect(org.mockito.ArgumentMatchers.any());

                assertTrue(queue.submit(reflection, conversation, "crashed-request"));
                jdbc.update("""
                        UPDATE minikun_reflection_job SET status = 'RUNNING', attempts = 1,
                            lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                        WHERE request_id = 'crashed-request'
                        """);
                queue.poll();
                awaitCompleted(jdbc, 2);
                assertEquals(2, jdbc.queryForObject("""
                        SELECT attempts FROM minikun_reflection_job WHERE request_id = 'crashed-request'
                        """, Integer.class));
                assertEquals(0L, jdbc.queryForObject("""
                        SELECT COUNT(*) FROM minikun_reflection_job WHERE messages_json <> ''
                        """, Long.class));
            }
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private void awaitCompleted(JdbcTemplate jdbc, long expected) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            long completed = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM minikun_reflection_job WHERE status = 'COMPLETED'
                    """, Long.class);
            if (completed == expected) return;
            Thread.sleep(20);
        }
        assertEquals(expected, jdbc.queryForObject("""
                SELECT COUNT(*) FROM minikun_reflection_job WHERE status = 'COMPLETED'
                """, Long.class));
    }
}
