package com.minikun.agent.minikun_agent.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.PostgresChatMemoryRepositoryDialect;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;

/** Opt-in verification against a real PostgreSQL instance used by deployment smoke tests. */
@EnabledIfEnvironmentVariable(named = "MINIKUN_POSTGRES_INTEGRATION", matches = "true")
class ConversationSummaryPostgresIntegrationTest {
    @Test
    void migrationAndStoreRoundTripAgainstPostgres() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                environment("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:5432/minikun"),
                environment("SPRING_DATASOURCE_USERNAME", "minikun"),
                environment("SPRING_DATASOURCE_PASSWORD", ""));
        new ResourceDatabasePopulator(new FileSystemResource(
                "deploy/migrations/V20260822_01__conversation_summary.sql")).execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource(
                "org/springframework/ai/chat/memory/repository/jdbc/schema-postgresql.sql"))
                .execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("jdbcTemplate", jdbc);
        ResilientConversationSummaryStore store = new ResilientConversationSummaryStore(
                beans.getBeanProvider(JdbcTemplate.class));
        ConversationId conversationId = new ConversationId(UUID.randomUUID().toString());
        ConversationSummary expected = new ConversationSummary(
                "integration-owner", conversationId, "- persistent summary",
                List.of("fingerprint"), 1, Instant.now());
        ConversationMemoryService memory = new ConversationMemoryService(
                MessageWindowChatMemory.builder()
                        .chatMemoryRepository(JdbcChatMemoryRepository.builder()
                                .jdbcTemplate(jdbc)
                                .dataSource(dataSource)
                                .dialect(new PostgresChatMemoryRepositoryDialect())
                                .build())
                        .maxMessages(20)
                        .build());

        try {
            store.save(expected);
            ConversationSummary loaded = store.find("integration-owner", conversationId).orElseThrow();
            assertEquals(expected.content(), loaded.content());
            assertEquals(expected.coveredFingerprints(), loaded.coveredFingerprints());
            assertEquals(expected.summarizedMessages(), loaded.summarizedMessages());
            assertTrue(store.delete("integration-owner", conversationId));
            assertTrue(store.find("integration-owner", conversationId).isEmpty());
            memory.appendTurn(conversationId,
                    new ChatMessage("user", "atomic question"),
                    new ChatMessage("assistant", "atomic answer"));
            assertEquals(List.of(
                    new ChatMessage("user", "atomic question"),
                    new ChatMessage("assistant", "atomic answer")), memory.load(conversationId));
        } finally {
            memory.clear(conversationId);
            jdbc.update("DELETE FROM minikun_conversation_summary WHERE owner_id = ? AND conversation_id = ?",
                    "integration-owner", conversationId.value());
        }
    }

    private String environment(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }
}
