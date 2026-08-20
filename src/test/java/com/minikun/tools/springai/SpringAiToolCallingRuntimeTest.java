package com.minikun.tools.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.mockito.ArgumentCaptor;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.model.ActiveModelConfiguration;
import com.minikun.model.ActiveChatModelProvider;
import com.minikun.model.ChatModelId;
import com.minikun.model.DefaultActiveChatModelProvider;
import com.minikun.model.DefaultChatModelProviderRegistry;
import com.minikun.model.ChatModelProvider;
import com.minikun.model.ModelCapabilities;
import com.minikun.model.existing.ExistingChatModelProvider;
import com.minikun.tools.CalculatorAddTool;
import com.minikun.tools.DefaultToolExecutor;
import com.minikun.tools.DefaultToolRegistry;
import com.minikun.tools.Tool;
import com.minikun.tools.ToolDefinition;

class SpringAiToolCallingRuntimeTest {
    @Test
    void executesNativeToolCallAndReturnsFinalAssistantResponse() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse toolRequest = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "calculator.add", "{\"a\":2,\"b\":3}")))
                        .build())));
        ChatResponse finalResponse = new ChatResponse(List.of(new Generation(
                new AssistantMessage("The answer is 5."))));
        when(chatModel.call(any(Prompt.class))).thenReturn(toolRequest, finalResponse);

        SpringAiToolCallingRuntime runtime = new SpringAiToolCallingRuntime(
                new DefaultActiveChatModelProvider(
                        new ActiveModelConfiguration(ChatModelId.EXISTING),
                        new DefaultChatModelProviderRegistry(List.of(new ExistingChatModelProvider(chatModel)))),
                List.of(new CalculatorAddTool()),
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(new CalculatorAddTool()))),
                new ObjectMapper());

        ChatResponse response = runtime.call(new Prompt("Add 2 and 3.",
                OllamaChatOptions.builder().disableThinking().numCtx(16_384).build()),
                new ConversationId("conversation"));

        assertEquals("The answer is 5.", response.getResult().getOutput().getText());
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(prompts.capture());
        assertEquals(true, prompts.getAllValues().stream()
                .allMatch(prompt -> prompt.getOptions() instanceof OllamaChatOptions));
        assertEquals(true, prompts.getAllValues().stream()
                .map(prompt -> (OllamaChatOptions) prompt.getOptions())
                .allMatch(options -> Boolean.FALSE.equals(options.getThinkOption().toJsonValue())));
        assertEquals(true, prompts.getAllValues().stream()
                .map(prompt -> (OllamaChatOptions) prompt.getOptions())
                .allMatch(options -> Integer.valueOf(16_384).equals(options.getNumCtx())));
        String continuation = prompts.getAllValues().get(1).getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::responseData)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        assertEquals(true, continuation.contains("assistant_instruction"));
    }

    @Test
    void supportsMultipleToolContinuationsUntilFinalAssistantResponse() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse firstToolRequest = toolRequest("call-1", 2, 3);
        ChatResponse secondToolRequest = toolRequest("call-2", 4, 6);
        ChatResponse finalResponse = new ChatResponse(List.of(new Generation(
                new AssistantMessage("The verified results are 5 and 10."))));
        when(chatModel.call(any(Prompt.class))).thenReturn(firstToolRequest, secondToolRequest, finalResponse);

        SpringAiToolCallingRuntime runtime = new SpringAiToolCallingRuntime(
                new DefaultActiveChatModelProvider(
                        new ActiveModelConfiguration(ChatModelId.EXISTING),
                        new DefaultChatModelProviderRegistry(List.of(new ExistingChatModelProvider(chatModel)))),
                List.of(new CalculatorAddTool()),
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(new CalculatorAddTool()))),
                new ObjectMapper());

        ChatResponse response = runtime.call(new Prompt("Calculate both sums."), new ConversationId("conversation"));

        assertEquals("The verified results are 5 and 10.", response.getResult().getOutput().getText());
        verify(chatModel, times(3)).call(any(Prompt.class));
    }

    @Test
    void returnsSafeResponseWhenToolContinuationLimitIsReached() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse[] toolRequests = new ChatResponse[5];
        for (int index = 0; index < toolRequests.length; index++) {
            toolRequests[index] = toolRequest("call-" + index, index, 1);
        }
        when(chatModel.call(any(Prompt.class))).thenReturn(toolRequests[0], toolRequests[1], toolRequests[2],
                toolRequests[3], toolRequests[4]);

        SpringAiToolCallingRuntime runtime = new SpringAiToolCallingRuntime(
                new DefaultActiveChatModelProvider(
                        new ActiveModelConfiguration(ChatModelId.EXISTING),
                        new DefaultChatModelProviderRegistry(List.of(new ExistingChatModelProvider(chatModel)))),
                List.of(new CalculatorAddTool()),
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(new CalculatorAddTool()))),
                new ObjectMapper());

        ChatResponse response = runtime.call(new Prompt("Keep calculating."), new ConversationId("conversation"));

        assertEquals(true, response.getResult().getOutput().getText().contains("ยังไม่เสร็จสมบูรณ์"));
        verify(chatModel, times(5)).call(any(Prompt.class));
    }

    @Test
    void returnsDeterministicConfirmationMessageBeforeFinalModelTurn() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse toolRequest = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "confirm-call", "function", "confirmation.tool", "{}")))
                        .build())));
        when(chatModel.call(any(Prompt.class))).thenReturn(toolRequest,
                new ChatResponse(List.of(new Generation(new AssistantMessage("model should not answer")))));
        Tool confirmationTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("confirmation.tool", "create a pending confirmation", Map.of());
            }

            @Override
            public com.minikun.tools.ToolResult execute(
                    com.minikun.tools.ToolCallContext context, Map<String, Object> arguments) {
                return com.minikun.tools.ToolResult.success(Map.of(
                        "requires_confirmation", true,
                        "message", "ยังไม่ได้บันทึกครับ กรุณายืนยันก่อน"));
            }
        };
        SpringAiToolCallingRuntime runtime = new SpringAiToolCallingRuntime(
                new DefaultActiveChatModelProvider(
                        new ActiveModelConfiguration(ChatModelId.EXISTING),
                        new DefaultChatModelProviderRegistry(List.of(
                                new ExistingChatModelProvider(chatModel)))),
                List.of(confirmationTool),
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(confirmationTool))),
                new ObjectMapper());

        ChatResponse response = runtime.call(new Prompt("Create something."), new ConversationId("conversation"));

        assertEquals("ยังไม่ได้บันทึกครับ กรุณายืนยันก่อน", response.getResult().getOutput().getText());
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    private ChatResponse toolRequest(String callId, int a, int b) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                callId, "function", "calculator.add", "{\"a\":" + a + ",\"b\":" + b + "}")))
                        .build())));
    }

    @Test
    void callbackMakesSuccessfulResultExplicitForFinalMcsAnswer() throws Exception {
        CalculatorAddTool tool = new CalculatorAddTool();
        SpringAiToolCallback callback = new SpringAiToolCallback(
                tool,
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(tool))),
                new ObjectMapper());

        var result = new ObjectMapper().readTree(callback.call("{\"a\":2,\"b\":3}"));

        assertEquals(true, result.get("success").asBoolean());
        assertEquals("calculator.add", result.get("tool").asText());
        assertEquals("5", result.get("result").asText());
        assertEquals(true, result.get("assistant_instruction").asText().contains("verified result"));
        assertEquals(true, result.get("assistant_instruction").asText().contains("MCS"));
    }

    @Test
    void callbackExposesDomainDefinitionAsJsonSchema() throws Exception {
        CalculatorAddTool tool = new CalculatorAddTool();
        SpringAiToolCallback callback = new SpringAiToolCallback(
                tool,
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(tool))),
                new ObjectMapper());

        var schema = new ObjectMapper().readTree(callback.getToolDefinition().inputSchema());

        assertEquals("calculator.add", callback.getToolDefinition().name());
        assertEquals("number", schema.get("properties").get("a").get("type").asText());
        assertEquals("[\"a\",\"b\"]", schema.get("required").toString());
    }

        @Test
        void rejectsToolCallingWhenActiveProviderDoesNotSupportIt() {
                ChatModelProvider tinyGradProvider = new ChatModelProvider() {
                        @Override
                        public ChatModelId id() {
                                return ChatModelId.TINYGRAD;
                        }

                        @Override
                        public ModelCapabilities capabilities() {
                                return new ModelCapabilities(true, false, false);
                        }

                        @Override
                        public ChatResponse chat(Prompt prompt) {
                                throw new AssertionError("TinyGrad must not be invoked for unsupported tools");
                        }

                        @Override
                        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
                                throw new AssertionError("TinyGrad must not stream tool calls");
                        }
                };
                SpringAiToolCallingRuntime runtime = new SpringAiToolCallingRuntime(
                                new DefaultActiveChatModelProvider(
                                                ActiveModelConfiguration.parse("tinygrad"),
                                                new DefaultChatModelProviderRegistry(List.of(tinyGradProvider))),
                                List.of(new CalculatorAddTool()),
                                new DefaultToolExecutor(new DefaultToolRegistry(List.of(new CalculatorAddTool()))),
                                new ObjectMapper());

                IllegalStateException exception = assertThrows(
                                IllegalStateException.class,
                                () -> runtime.call(new Prompt("Add 2 and 3."), new ConversationId("conversation")));

                assertEquals(true, exception.getMessage().contains("TINYGRAD"));
        }
}
