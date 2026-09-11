package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import com.minikun.model.GenerationOptions;
import com.minikun.model.OllamaReasoning;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaChatOptions;

class ReasoningOptionsTest {
    @Test
    void parsesPerRequestEffortAndRoutesAutoWithoutEnablingUnsupportedModels() throws Exception {
        var request = new ObjectMapper().readValue("""
            {"model":"mini-kun","messages":[{"role":"user","content":"ช่วยคิด"}],"reasoning_effort":"high"}
            """, ChatCompletionRequest.class);
        var resolver = new ChatGenerationOptionsResolver();
        var result = resolver.resolve(request, "ช่วยคิด", null, false, false, false, false, null, 1024, .3, null, null);
        assertEquals(GenerationOptions.Reasoning.HIGH, result.options().reasoning());
        var normal = new ChatCompletionRequest("mini-kun", List.of(new Message("user", "สวัสดี")), "chat", false, null, null, null);
        assertEquals(GenerationOptions.Reasoning.OFF, resolver.resolve(normal, "สวัสดี", null, false, false, false, false, null, 1024, .3, null, null).options().reasoning());
        assertEquals(GenerationOptions.Reasoning.MEDIUM, resolver.resolve(normal, "research", null, false, false, true, false, null, 1024, .3, null, null).options().reasoning());
        assertEquals(false, OllamaReasoning.wire("unknown-alias", GenerationOptions.Reasoning.HIGH));
        assertEquals(true, OllamaReasoning.wire("qwen3:4b", GenerationOptions.Reasoning.HIGH));
        assertEquals(true, OllamaReasoning.wire("qwen3.5:0.6b", GenerationOptions.Reasoning.HIGH));
        assertEquals(true, OllamaReasoning.wire("hf.co/empero-ai/Qwen3.8-2B-Distill-GGUF:Q6_K", GenerationOptions.Reasoning.HIGH));
        assertEquals(false, OllamaReasoning.wire("qwen3:4b", GenerationOptions.Reasoning.OFF));
        assertEquals("low", OllamaReasoning.wire("gpt-oss:20b", GenerationOptions.Reasoning.OFF));
        var builder = OllamaChatOptions.builder();
        OllamaReasoning.apply(builder, "qwen3:4b", GenerationOptions.Reasoning.HIGH);
        assertEquals(org.springframework.ai.ollama.api.ThinkOption.ThinkBoolean.ENABLED, builder.build().getThinkOption());
    }
}
