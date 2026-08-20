package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.communication.CommunicationService;
import com.minikun.model.ChatModelId;
import com.minikun.model.ChatModelProvider;
import com.minikun.model.ModelCapabilities;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class CommunicationAssistantToolTest {
    @Test
    void usesOwnerFromToolContextAndReturnsDraftOnlyResult() {
        CapturingModel model = new CapturingModel();
        CommunicationAssistantTool tool = new CommunicationAssistantTool(
                new CommunicationService(() -> model, new ObjectMapper(), null,
                        true, 12000, 16000, 1600));

        ToolResult result = tool.execute(
                new ToolCallContext(new ConversationId("conversation"), "call", "owner-77"),
                Map.of("action", "reply", "content", "Can we move the meeting?",
                        "channel", "email", "language", "en"));

        assertTrue(result.success());
        assertTrue(model.prompt.getInstructions().getLast().getText().contains("owner-77") == false);
        Object value = result.value();
        assertTrue(value instanceof com.minikun.communication.CommunicationResult);
        assertEquals(false, ((com.minikun.communication.CommunicationResult) value).sendSupported());
    }

    @Test
    void rejectsInvalidActionAsToolArgumentError() {
        CommunicationAssistantTool tool = new CommunicationAssistantTool(
                new CommunicationService(() -> new CapturingModel(), new ObjectMapper(), null,
                        true, 12000, 16000, 1600));

        ToolResult result = tool.execute(
                new ToolCallContext(new ConversationId("conversation"), "call"),
                Map.of("action", "send", "content", "hello"));

        assertEquals(false, result.success());
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, result.errorCode());
    }

    @Test
    void definitionNeverOffersSending() {
        CommunicationAssistantTool tool = new CommunicationAssistantTool(
                new CommunicationService(() -> new CapturingModel(), new ObjectMapper(), null,
                        true, 12000, 16000, 1600));

        assertEquals("communication.assist", tool.definition().name());
        assertTrue(tool.definition().description().contains("never sends"));
        assertTrue(tool.definition().parameters().get("action").description().contains("summarize"));
    }

    private static final class CapturingModel implements ChatModelProvider {
        private Prompt prompt;
        @Override public ChatModelId id() { return ChatModelId.EXISTING; }
        @Override public ModelCapabilities capabilities() { return new ModelCapabilities(false, false, false); }
        @Override public ChatResponse chat(Prompt prompt) {
            this.prompt = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage("Draft response"))));
        }
        @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.empty(); }
    }
}
