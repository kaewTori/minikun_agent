package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

class ResponseContinuationTest {
    private final ResponseContinuation continuation = new ResponseContinuation(true, 2_000, 1_024);

    @Test
    void continuesAnyResponseThatActuallyHitTheLengthLimit() {
        assertTrue(continuation.shouldContinue(response("ค้าง", "length")));
        assertTrue(continuation.shouldContinue(response("ค้าง", "MAX_TOKENS")));
        assertFalse(continuation.shouldContinue(response("จบ", "stop")));
        assertFalse(new ResponseContinuation(false, 2_000, 1_024)
                .shouldContinue(response("ค้าง", "length")));
    }

    @Test
    void buildsABoundedNaturalEndingPromptForCreativeResponses() {
        Prompt original = originalPrompt();
        Prompt result = continuation.continuationPrompt(
                original, "discarded-start-" + "ข".repeat(3_000) + "-draft-end", "creative");

        assertEquals(1_024, result.getOptions().getMaxTokens());
        assertTrue(result.getContents().contains("natural stopping point"));
        assertTrue(result.getContents().contains("แต่งเรื่องของรินให้จบ"));
        assertTrue(result.getContents().contains("-draft-end"));
        assertFalse(result.getContents().contains("discarded-start-"));
        assertTrue(result.getContents().length() < 12_500);
    }

    @Test
    void buildsEvidenceSafePromptForGeneralResponses() {
        Prompt result = continuation.continuationPrompt(originalPrompt(), "หลักฐานช่วงท้าย", "search");

        assertTrue(result.getContents().contains("finish it concisely"));
        assertTrue(result.getContents().contains("citations"));
        assertTrue(result.getContents().contains("not supported by the original context"));
    }

    @Test
    void removesRepeatedTextAtTheJoin() {
        assertEquals("ก่อนประตูเปิดออกและรินก็ก้าวเข้าไป",
                continuation.appendWithoutRepeating(
                        "ก่อนประตูเปิดออก", "ประตูเปิดออกและรินก็ก้าวเข้าไป"));
    }

    private Prompt originalPrompt() {
        return new Prompt(List.of(
                new SystemMessage("persona\n\n[Conversation]\n" + "ก".repeat(8_000)
                        + "\n\n[Capabilities]\ncreative"),
                new UserMessage("แต่งเรื่องของรินให้จบ")),
                ChatOptions.builder().maxTokens(4_096).temperature(0.7).build());
    }

    private ChatResponse response(String text, String finishReason) {
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(finishReason).build())));
    }
}
