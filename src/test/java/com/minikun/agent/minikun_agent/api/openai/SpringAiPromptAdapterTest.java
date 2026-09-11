package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.model.ChatModelId;
import com.minikun.model.GenerationOptions;
import com.minikun.pcs.model.PromptMessage;
import com.minikun.pcs.model.PromptRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaChatOptions;

class SpringAiPromptAdapterTest {
    private final SpringAiPromptAdapter adapter = new SpringAiPromptAdapter();

    @Test
    void appliesBalancedOllamaSamplingAndKeepsThinkingOff() {
        OllamaChatOptions options = adapt();

        assertEquals("model", options.getModel());
        assertEquals(16_384, options.getNumCtx());
        assertEquals(64, options.getTopK());
        assertEquals(0.9, options.getTopP());
        assertEquals(0.05, options.getMinP());
        assertEquals(1.1, options.getRepeatPenalty());
        assertEquals(0.7, options.getTemperature());
        assertEquals(800, options.getMaxTokens());
        assertEquals(List.of("END"), options.getStopSequences());
        assertEquals(false, options.getThinkOption().toJsonValue());
    }

    @Test
    void putsQwenNoThinkDirectiveOnTheLatestUserTurn() {
        var prompt = new com.minikun.pcs.model.Prompt(List.of(
                new PromptMessage(PromptRole.SYSTEM, "persona"),
                new PromptMessage(PromptRole.USER, "ตอบสั้น ๆ")));
        var adapted = adapter.adapt(prompt, new GenerationOptions(0.0, 200, List.of()),
                ChatModelId.EXISTING, "qwen3:4b", 8_192);
        assertTrue(adapted.getInstructions().getLast().getText().endsWith("/no_think"));
    }

    private OllamaChatOptions adapt() {
        var prompt = new com.minikun.pcs.model.Prompt(List.of(
                new PromptMessage(PromptRole.USER, "เขียนฉากสั้น ๆ")));
        return (OllamaChatOptions) adapter.adapt(
                prompt,
                new GenerationOptions(0.7, 800, List.of("END")),
                ChatModelId.EXISTING,
                "model",
                16_384)
                .getOptions();
    }
}
