package com.minikun.memory.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.*;
import com.minikun.memory.model.*;
import com.minikun.memory.reflection.*;
import com.minikun.model.task.OllamaTaskModelProvider;
import com.minikun.pcs.MinikunPersonaProvider;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.client.RestClient;

/** Opt-in real extraction + persistence + cross-conversation recall with synthetic user turns. */
@EnabledIfEnvironmentVariable(named = "MINIKUN_LIVE_MODEL_EVAL", matches = ".+")
class TemporalModelEvaluationTest {
    @Test
    void changesPreferenceAcrossConversationsAndRetainsHistory() {
        try (var source = new SingleConnectionDataSource(System.getenv("MINIKUN_EVAL_JDBC_URL"), "postgres", "", true)) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TEMP TABLE minikun_memory (LIKE public.minikun_memory INCLUDING ALL)");
            var repository = new JdbcMemoryRepository(jdbc);
            var json = new ObjectMapper();
            var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build());
            factory.setReadTimeout(Duration.ofSeconds(120));
            var provider = new OllamaTaskModelProvider(RestClient.builder().requestFactory(factory).baseUrl("http://127.0.0.1:11434/api/chat").build(),
                json, System.getenv("MINIKUN_LIVE_MODEL_EVAL"), Duration.ofSeconds(120), true);
            var service = new ReflectionService(new ReflectionPromptBuilder(json, mock(MinikunPersonaProvider.class)),
                prompt -> {
                    String extracted = new TaskModelReflectionProvider(provider, json).reflect(prompt);
                    System.out.println("Synthetic extraction: " + extracted);
                    return extracted;
                }, new ReflectionParser(json), new ReflectionDecisionService(),
                repository, Clock.systemUTC());
            service.reflect(turn("first", "ฉันชอบอาหารรสหวานเป็นปกติ", "2026-01-01T00:00:00Z"));
            assertEquals(1, repository.findLongTerm(new LongTermMemoryScope("eval-user"), 10).size(), "initial extraction");
            assertNotNull(repository.findLongTerm(new LongTermMemoryScope("eval-user"), 10).getFirst().fact(), "structured slot");
            service.reflect(turn("second", "เมื่อก่อนฉันชอบอาหารรสหวาน แต่ตอนนี้ไม่ชอบอาหารรสหวานแล้ว", "2026-02-01T00:00:00Z"));
            var current = repository.findLongTerm(new LongTermMemoryScope("eval-user"), 10);
            assertEquals(1, current.size(), "same slot must replace old preference");
            assertTrue(current.getFirst().content().contains("ไม่"), "current value is a withdrawal");
            assertEquals(2, repository.findByOwner("eval-user", 10).size(), "old preference retained");
            service.reflect(turn("third", "วันนี้ขออาหารไม่หวานแค่มื้อนี้", "2026-03-01T00:00:00Z"));
            assertEquals(2, repository.findByOwner("eval-user", 10).size(), "temporary request must not persist");
            assertTrue(repository.findLongTerm(new LongTermMemoryScope("different-user"), 10).isEmpty());
        }
    }
    private CompletedConversation turn(String conversation, String text, String time) {
        return new CompletedConversation("eval-user", conversation,
            List.of(new CompletedConversation.Message("user", text)), Instant.parse(time));
    }
}
