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
import com.minikun.visual.StoryIllustrationService;

class OpenAiChatResponseFactoryTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiChatResponseFactory factory = new OpenAiChatResponseFactory(objectMapper);

    @Test
    void mapsCompletionContentUsageAndAttachments() {
        ChatAttachment attachment = new ChatAttachment("image_url", "https://example.test/image.jpg", "sample");

        var response = factory.completion(
                "chatcmpl-1", 123L, "mini-kun", "hello", new ModelUsage(5, 7),
                List.of(attachment), "length");

        assertEquals("chat.completion", response.object());
        assertEquals("assistant", response.choices().getFirst().message().role());
        assertEquals("hello", response.choices().getFirst().message().content());
        assertEquals(12, response.usage().total_tokens());
        assertEquals("length", response.choices().getFirst().finish_reason());
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
    void emitsRichImageAttachmentsInInitialStreamingChunk() throws Exception {
        ChatAttachment attachment = new ChatAttachment(
                "image",
                "/v1/images/proxy?url=https%3A%2F%2Fimages.example%2Fwork.jpg",
                "Representative work",
                "https://artist.example/gallery",
                "Artwork description",
                "web",
                "https://images.example/work.jpg",
                "https://images.example/thumb.jpg",
                1200,
                800,
                "searxng",
                "");

        JsonNode chunk = objectMapper.readTree(factory.initialChunk(
                "id", 123L, "mini-kun", List.of(attachment)));

        assertEquals(1, chunk.path("attachments").size());
        assertEquals("Representative work", chunk.at("/attachments/0/title").asText());
        assertEquals(attachment.url(), chunk.at("/choices/0/delta/images/0/image_url/url").asText());
    }

    @Test
    void emitsGeneratedAttachmentAfterStreamingText() throws Exception {
        ChatAttachment attachment = new ChatAttachment(
                "image", "/v1/images/generated/example.png", "Story illustration",
                "", "Generated for this story", "generated",
                "/v1/images/generated/example.png", "", null, null, "gpt-image-2", "");

        JsonNode chunk = objectMapper.readTree(factory.attachmentChunk(
                "id", 123L, "mini-kun", List.of(attachment)));

        assertEquals("generated", chunk.at("/attachments/0/origin").asText());
        assertEquals(attachment.url(), chunk.at("/choices/0/delta/images/0/image_url/url").asText());
    }

    @Test
    void emitsVisibleStreamingNoticeWhenStoryIllustrationFails() throws Exception {
        var illustration = new StoryIllustrationService.IllustrationResult(
                List.of(), "\n\n> ⚠️ สร้างภาพประกอบไม่สำเร็จ");

        List<String> chunks = factory.illustrationChunks("id", 123L, "mini-kun", illustration);

        assertEquals(1, chunks.size());
        assertTrue(objectMapper.readTree(chunks.getFirst())
                .at("/choices/0/delta/content").asText().contains("สร้างภาพประกอบไม่สำเร็จ"));
    }

    @Test
    void omitsUsageChunkWhenProviderDidNotReportUsage() {
        assertTrue(factory.usageChunk(ModelUsage.empty(), "id", 123L, "mini-kun").isEmpty());
        assertFalse(factory.usageChunk(new ModelUsage(1, 2), "id", 123L, "mini-kun").isEmpty());
    }

    @Test
    void emitsAlreadyNormalizedStreamingContent() throws Exception {
        String chunk = factory.contentChunk("[Docs](https://example.test/docs)", "id", 123L, "mini-kun");

        assertEquals("[Docs](https://example.test/docs)",
                objectMapper.readTree(chunk).at("/choices/0/delta/content").asText());
    }

    @Test
    void preservesStreamingLengthFinishReason() throws Exception {
        JsonNode chunk = objectMapper.readTree(factory.stopChunk(
                "id", 123L, "mini-kun", "length"));

        assertEquals("length", chunk.at("/choices/0/finish_reason").asText());
    }

    @Test
    void announcesImageGenerationWithoutAddingStoryText() throws Exception {
        JsonNode chunk = objectMapper.readTree(factory.imageStatusChunk("id", 123L, "mini-kun"));

        assertEquals("running", chunk.path("image_status").asText());
        assertTrue(chunk.path("choices").isEmpty());
    }
}
