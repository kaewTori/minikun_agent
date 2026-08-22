package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import com.minikun.model.ModelUsage;

class OpenAiChatResponseFactoryTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiChatResponseFactory factory = new OpenAiChatResponseFactory(objectMapper);

    @Test
    void mapsCompletionContentUsageAndAttachments() {
        ChatAttachment attachment = new ChatAttachment("image_url", "https://example.test/image.jpg", "sample");

        var response = factory.completion(
                "chatcmpl-1", 123L, "mini-kun", "hello", new ModelUsage(5, 7), List.of(attachment));

        assertEquals("chat.completion", response.object());
        assertEquals("assistant", response.choices().getFirst().message().role());
        assertEquals("hello", response.choices().getFirst().message().content());
        assertEquals(12, response.usage().total_tokens());
        assertEquals(List.of(attachment), response.attachments());
    }

    @Test
    void emitsInitialContentStopAndDoneChunksInOrder() throws Exception {
        List<String> chunks = factory.contentStream("mini-kun", "ready");

        assertEquals(3, chunks.size());
        JsonNode content = objectMapper.readTree(chunks.get(0));
        JsonNode stop = objectMapper.readTree(chunks.get(1));
        assertEquals("assistant", content.at("/choices/0/delta/role").asText());
        assertEquals("ready", content.at("/choices/0/delta/content").asText());
        assertEquals("stop", stop.at("/choices/0/finish_reason").asText());
        assertEquals("[DONE]", chunks.get(2));
    }

    @Test
    void omitsUsageChunkWhenProviderDidNotReportUsage() {
        assertTrue(factory.usageChunk(ModelUsage.empty(), "id", 123L, "mini-kun").isEmpty());
        assertFalse(factory.usageChunk(new ModelUsage(1, 2), "id", 123L, "mini-kun").isEmpty());
    }
}
