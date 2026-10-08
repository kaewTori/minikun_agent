package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.task.*;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class TurnAmbiguityResolverTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="minikun.eval.live", matches="true")
    void liveNativeDecisionKeepsAnExplanationOnTheDirectPath() {
        var provider = new OllamaTaskModelProvider(org.springframework.web.client.RestClient.builder()
                .baseUrl("http://127.0.0.1:11434/api/chat").requestInterceptor((request, body, execution) -> {
                    java.nio.file.Files.write(java.nio.file.Path.of("target/decision-improvement/live-intent-wire.json"), body);
                    return execution.execute(request, body);
                }).build(), new ObjectMapper(),
                "hf.co/mradermacher/llama3.2-typhoon2-3b-GGUF:Q4_K_M", Duration.ofSeconds(10), true);
        var result = resolver(provider).resolve("ต่อ", "User: I want to understand this Java code. Assistant: I can continue explaining.").orElseThrow();
        assertEquals(TurnPlan.Intent.TECHNICAL, result.intent());
        assertFalse(result.needsTools());
        assertFalse(result.background());
    }

    @Test
    void derivesExecutionFlagsAndRejectsModelOwnedFlagsOrUnknownIntents() {
        for (String intent : new String[]{"technical", "work", "general", "action", "search", "research"}) {
            var resolver = resolver(request -> {
                assertEquals(32, request.maxOutputTokens());
                assertFalse(request.messages().getLast().content().contains("/no_think"));
                assertNotNull(request.responseSchema());
                return "{\"intent\":\"" + intent + "\"}";
            });
            var result = resolver.resolve("ทำเลย", "user: เป้าหมายในบริบท").orElseThrow();
            assertEquals(java.util.Set.of("action", "search", "research").contains(intent), result.needsTools());
            assertEquals("research".equals(intent), result.background());
        }
        for (String response : new String[]{"{\"intent\":\"technical\",\"needsTools\":true}",
                "{\"intent\":\"unknown\"}", "{\"intent\":true}", "[]", "not json"}) {
            assertTrue(resolver(request -> response).resolve("ทำเลย", "user: context").isEmpty());
        }
    }

    private TurnAmbiguityResolver resolver(TaskModelProvider provider) {
        var beans = new StaticListableBeanFactory(Map.of("models", new TaskModelRegistry(Map.of(TaskModelId.OLLAMA, provider))));
        return new TurnAmbiguityResolver(beans.getBeanProvider(TaskModelRegistry.class), new ObjectMapper(),
                true, Duration.ofSeconds(1));
    }
}
