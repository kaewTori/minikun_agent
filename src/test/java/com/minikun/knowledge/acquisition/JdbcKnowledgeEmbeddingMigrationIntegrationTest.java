package com.minikun.knowledge.acquisition;

import static com.minikun.knowledge.acquisition.KnowledgeAcquisitionModels.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.knowledge.JdbcPersonalKnowledgeRepository;
import com.minikun.knowledge.KnowledgeChunkDraft;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Connection-local temporary copies; never mutates the live knowledge tables. */
@EnabledIfEnvironmentVariable(named = "MINIKUN_EVAL_JDBC_URL", matches = ".+")
class JdbcKnowledgeEmbeddingMigrationIntegrationTest {
    @Test
    void updatesOnlyVectorsChecksDocumentModelAndDoesNotOverwriteConcurrentClaimEdits() {
        try (var source = new SingleConnectionDataSource(System.getenv("MINIKUN_EVAL_JDBC_URL"),
                System.getenv().getOrDefault("MINIKUN_EVAL_JDBC_USER", "minikun"),
                System.getenv().getOrDefault("MINIKUN_EVAL_JDBC_PASSWORD", ""), true)) {
            var jdbc = new JdbcTemplate(source);
            for (String table : List.of("minikun_knowledge_claim", "minikun_knowledge_source", "minikun_knowledge_chunk"))
                jdbc.execute("CREATE TEMP TABLE " + table + " (LIKE public." + table + " INCLUDING ALL)");
            var store = new KnowledgeAcquisitionStore(jdbc, new ObjectMapper());
            Instant now = Instant.parse("2026-10-07T00:00:00Z");
            Claim original = new Claim(UUID.randomUUID(), UUID.randomUUID(), "owner", "Knowledge", "Old text",
                    "fixture", ClaimStatus.RETRACTED, .9, List.of("https://example.com/evidence"), "reason",
                    "1,0", "old-model", now, now, now, now.plusSeconds(86400), now);
            store.saveClaim(original);
            assertEquals(original, store.embeddingsToRefresh("new-model", 64).getFirst());
            store.updateEmbedding(original, "0,1", "new-model");
            Claim refreshed = store.claim("owner", original.id()).orElseThrow();
            assertEquals("new-model", refreshed.embeddingModel());
            assertEquals(original.status(), refreshed.status());
            assertEquals(original.expiresAt(), refreshed.expiresAt());
            assertEquals(original.updatedAt(), refreshed.updatedAt());
            assertTrue(store.embeddingsToRefresh("new-model", 64).isEmpty());
            jdbc.update("UPDATE minikun_knowledge_claim SET claim_text = 'Concurrent edit' WHERE id = ?", original.id());
            store.updateEmbedding(original, "9,9", "wrong-model");
            assertEquals("new-model", store.claim("owner", original.id()).orElseThrow().embeddingModel());
            var repository = new JdbcPersonalKnowledgeRepository(jdbc,
                    new TransactionTemplate(new DataSourceTransactionManager(source)));
            UUID id = repository.begin("owner", "knowledge", "file.md", "file.md");
            assertFalse(repository.embeddingsCurrent(id, "new-model"));
            repository.replace(id, "hash", now, now, List.of(new KnowledgeChunkDraft(0, "", "text", "hash", "1,0", "old-model")));
            assertTrue(repository.embeddingsCurrent(id, "old-model"));
            assertFalse(repository.embeddingsCurrent(id, "new-model"));
            jdbc.update("UPDATE minikun_knowledge_chunk SET embedding = '' WHERE source_id = ?", id);
            assertFalse(repository.embeddingsCurrent(id, "old-model"));
        }
    }
}
