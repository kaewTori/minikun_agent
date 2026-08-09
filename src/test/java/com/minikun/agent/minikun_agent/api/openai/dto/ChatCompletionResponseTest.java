package com.minikun.agent.minikun_agent.api.openai.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ChatCompletionResponseTest {
    @Test
    void legacyConstructorUsesEmptyAttachments() {
        ChatCompletionResponse response = response();

        assertEquals("response-id", response.id());
        assertEquals("chat.completion", response.object());
        assertEquals(42L, response.created());
        assertEquals("model", response.model());
        assertEquals(1, response.choices().size());
        assertEquals(new ChatCompletionResponse.Usage(1, 2, 3), response.usage());
        assertEquals(List.of(), response.attachments());
        assertNotNull(response.attachments());
    }

    @Test
    void attachmentsAreDefensivelyCopied() {
        List<ChatAttachment> attachments = new ArrayList<>();
        ChatAttachment attachment = new ChatAttachment("image", "image-url", "title");
        attachments.add(attachment);

        ChatCompletionResponse response = new ChatCompletionResponse(
                "id", "object", 1L, "model", List.of(), new ChatCompletionResponse.Usage(0, 0, 0),
                attachments);
        attachments.clear();

        assertEquals(List.of(attachment), response.attachments());
        assertThrows(UnsupportedOperationException.class, () -> response.attachments().clear());
    }

    @Test
    void attachmentsSerializeAsTopLevelAdditiveField() throws Exception {
        ChatAttachment attachment = new ChatAttachment("image", "image-url", "title");
        ChatCompletionResponse response = new ChatCompletionResponse(
                "id", "object", 1L, "model", List.of(), new ChatCompletionResponse.Usage(0, 0, 0),
                List.of(attachment));

        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(response));

        assertEquals("id", json.get("id").asText());
        assertEquals("object", json.get("object").asText());
        assertEquals(1L, json.get("created").asLong());
        assertEquals("model", json.get("model").asText());
        assertEquals(1, json.get("attachments").size());
        assertEquals("image", json.get("attachments").get(0).get("type").asText());
        assertEquals("image-url", json.get("attachments").get(0).get("url").asText());
        assertEquals("title", json.get("attachments").get(0).get("title").asText());
    }

    @Test
    void emptyAttachmentsSerializeAsEmptyArray() throws Exception {
        JsonNode json = new ObjectMapper().readTree(
                new ObjectMapper().writeValueAsString(response()));

        assertEquals(0, json.get("attachments").size());
    }

    private ChatCompletionResponse response() {
        return new ChatCompletionResponse(
                "response-id", "chat.completion", 42L, "model",
                List.of(new ChatCompletionResponse.Choice(
                        0, new Message("assistant", "content"), "stop")),
                new ChatCompletionResponse.Usage(1, 2, 3));
    }
}