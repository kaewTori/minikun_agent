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

class CreativeResponseContinuationTest {
    private final CreativeResponseContinuation continuation =
            new CreativeResponseContinuation(true, 2_000, 1_024);

    @Test
    void continuesOnlyCreativeResponsesThatActuallyHitTheLengthLimit() {
        assertTrue(continuation.shouldContinue("creative", response("ค้าง", "length")));
        assertTrue(continuation.shouldContinue("creative", response("ค้าง", "MAX_TOKENS")));
        assertFalse(continuation.shouldContinue("creative", response("จบ", "stop")));
        assertFalse(continuation.shouldContinue("general", response("ค้าง", "length")));
    }

    @Test
    void buildsABoundedNaturalEndingPrompt() {
        Prompt original = new Prompt(List.of(
                new SystemMessage("persona\n\n[Conversation]\n" + "ก".repeat(8_000)
                        + "\n\n[Capabilities]\ncreative"),
                new UserMessage("แต่งเรื่องของรินให้จบ")),
                ChatOptions.builder().maxTokens(4_096).temperature(0.7).build());

        Prompt result = continuation.continuationPrompt(
                original, "discarded-start-" + "ข".repeat(3_000) + "-draft-end");
        String text = result.getContents();

        assertEquals(1_024, result.getOptions().getMaxTokens());
        assertTrue(text.contains("natural stopping point"));
        assertTrue(text.contains("แต่งเรื่องของรินให้จบ"));
        assertTrue(text.contains("-draft-end"));
        assertFalse(text.contains("discarded-start-"));
        assertTrue(text.length() < 12_500);
    }

    @Test
    void removesRepeatedTextAtTheJoin() {
        assertEquals("ก่อนประตูเปิดออกและรินก็ก้าวเข้าไป",
                continuation.appendWithoutRepeating(
                        "ก่อนประตูเปิดออก", "ประตูเปิดออกและรินก็ก้าวเข้าไป"));
    }

    private ChatResponse response(String text, String finishReason) {
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(finishReason).build())));
    }
}
