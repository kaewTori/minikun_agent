package com.minikun.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.tools.DefaultToolExecutor;
import com.minikun.tools.DefaultToolRegistry;
import com.minikun.tools.ToolCallContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import com.minikun.tools.springai.SpringAiToolCallback;

class PresentationToolTest {
    @TempDir Path directory;

    @Test
    void createsAndAttachesADeckFromStructuredToolArguments() {
        ObjectMapper mapper = new ObjectMapper();
        PresentationStore store = new PresentationStore(directory, 1_000_000, Duration.ofDays(1), mapper,
                Clock.systemUTC());
        PresentationTool tool = new PresentationTool(new PresentationService(mapper, null, store));
        ToolCallContext context = new ToolCallContext(new ConversationId("conversation"), "call", "owner");
        Map<String, Object> spec = Map.of(
                "title", "PostgreSQL Performance Tuning",
                "language", "th",
                "theme", "ocean",
                "slides", List.of(Map.of("title", "วัดก่อนปรับ", "layout", "cover", "body", "เริ่มจากหลักฐาน")));

        try (var scope = PresentationAttachmentScope.open()) {
            var result = tool.execute(context, Map.of("spec", spec));
            var response = scope.attach(new ChatResponse(List.of(new Generation(new AssistantMessage("พร้อมครับ")))));

            assertTrue(result.success());
            var attachments = PresentationAttachmentScope.attachments(response);
            assertEquals(1, attachments.size());
            assertEquals("presentation", attachments.getFirst().type());
            assertEquals(1, attachments.getFirst().slideCount());
            assertTrue(store.bytes(store.read(attachments.getFirst().artifactId(), "owner")).length > 0);
        }
    }

    @Test
    void exposesAConcreteNestedSlideSchemaToTheModel() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PresentationStore store = new PresentationStore(directory, 1_000_000, Duration.ofDays(1), mapper,
                Clock.systemUTC());
        PresentationTool tool = new PresentationTool(new PresentationService(mapper, null, store));
        SpringAiToolCallback callback = new SpringAiToolCallback(tool,
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(tool))), mapper);

        var schema = mapper.readTree(callback.getToolDefinition().inputSchema());
        var spec = schema.path("properties").path("spec");
        var slides = spec.path("properties").path("slides");

        assertEquals("object", spec.path("type").asText());
        assertEquals("array", slides.path("type").asText());
        assertEquals("string", slides.path("items").path("properties").path("title").path("type").asText());
        assertEquals("array", slides.path("items").path("properties").path("bullets").path("type").asText());
    }
}
