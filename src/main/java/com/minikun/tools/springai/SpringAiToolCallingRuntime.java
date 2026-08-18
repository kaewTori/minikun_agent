package com.minikun.tools.springai;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.model.ActiveChatModelProvider;
import com.minikun.model.ChatModelProvider;
import com.minikun.tools.Tool;
import com.minikun.tools.ToolExecutor;

@Component
public final class SpringAiToolCallingRuntime {
    private static final int MAX_TOOL_CONTINUATIONS = 4;
    private static final Logger LOGGER = LoggerFactory.getLogger(SpringAiToolCallingRuntime.class);

    private final ChatModelProvider chatModelProvider;
    private final ToolCallingManager toolCallingManager;
    private final List<ToolCallback> callbacks;
    private final ObjectMapper objectMapper;

    public SpringAiToolCallingRuntime(
            ActiveChatModelProvider activeChatModelProvider,
            List<Tool> tools,
            ToolExecutor toolExecutor,
            ObjectMapper objectMapper) {
        this.chatModelProvider = Objects.requireNonNull(
                activeChatModelProvider, "active chat model provider must not be null").get();
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        Objects.requireNonNull(tools, "tools must not be null");
        this.callbacks = tools.stream()
                .sorted((left, right) -> left.definition().name().compareTo(right.definition().name()))
                .map(tool -> new SpringAiToolCallback(tool, toolExecutor, objectMapper))
                .map(callback -> (ToolCallback) callback)
                .toList();
        this.toolCallingManager = ToolCallingManager.builder().build();
    }

    public ChatResponse call(Prompt prompt, ConversationId conversationId) {
        return call(prompt, conversationId, "default");
    }

    public ChatResponse call(Prompt prompt, ConversationId conversationId, String ownerId) {
        Objects.requireNonNull(prompt, "prompt must not be null");
        Objects.requireNonNull(conversationId, "conversation id must not be null");
        Objects.requireNonNull(ownerId, "owner id must not be null");
        if (!chatModelProvider.capabilities().toolCalling()) {
            throw new IllegalStateException(
                    "Chat model provider does not support tool calling: " + chatModelProvider.id());
        }
        // OllamaChatModel casts chat options to OllamaChatOptions. The generic
        // DefaultToolCallingChatOptions is not compatible with that adapter,
        // even though both implement ToolCallingChatOptions.
        OllamaChatOptions.Builder optionsBuilder = OllamaChatOptions.builder();
        copyChatOptions(prompt.getOptions(), optionsBuilder);
        ToolCallingChatOptions options = optionsBuilder
                .toolCallbacks(callbacks)
                .toolContext(Map.of("conversationId", conversationId.value(), "ownerId", ownerId))
                .build();
        Prompt currentPrompt = new Prompt(prompt.getInstructions(), options);
        ChatResponse response = chatModelProvider.chat(currentPrompt);
        int continuationCount = 0;
        while (hasToolCalls(response)) {
            if (continuationCount >= MAX_TOOL_CONTINUATIONS) {
                LOGGER.warn("process=tool_calling event=continuation_limit_reached conversation_id={} rounds={}",
                        conversationId.value(), continuationCount);
                return continuationLimitResponse();
            }

            continuationCount++;
            prepareCurrentCallIds(response);
            ToolExecutionResult executionResult;
            try {
                executionResult = toolCallingManager.executeToolCalls(currentPrompt, response);
            } finally {
                clearCurrentCallIds();
            }
            Optional<String> confirmationMessage = confirmationMessage(executionResult);
            if (confirmationMessage.isPresent()) {
                return assistantResponse(confirmationMessage.get());
            }
            LOGGER.debug("process=tool_calling event=continuation_completed conversation_id={} round={}",
                    conversationId.value(), continuationCount);
            currentPrompt = new Prompt(executionResult.conversationHistory(), options);
            response = chatModelProvider.chat(currentPrompt);
        }
        return response;
    }

    private void prepareCurrentCallIds(ChatResponse response) {
        List<AssistantMessage.ToolCall> toolCalls = response.getResults().stream()
                .filter(generation -> generation.getOutput() != null)
                .flatMap(generation -> generation.getOutput().getToolCalls().stream())
                .toList();
        callbacks.stream()
                .filter(SpringAiToolCallback.class::isInstance)
                .map(SpringAiToolCallback.class::cast)
                .forEach(callback -> callback.setCurrentCallIds(toolCalls.stream()
                        .filter(toolCall -> callback.getToolDefinition().name().equals(toolCall.name()))
                        .map(AssistantMessage.ToolCall::id)
                        .toList()));
    }

    private void clearCurrentCallIds() {
        callbacks.stream()
                .filter(SpringAiToolCallback.class::isInstance)
                .map(SpringAiToolCallback.class::cast)
                .forEach(SpringAiToolCallback::clearCurrentCallId);
    }

    private ChatResponse continuationLimitResponse() {
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage(
                        "ขออภัยครับ ตอนนี้การประมวลผลด้วยเครื่องมือยังไม่เสร็จสมบูรณ์ "
                                + "มินิคุงจึงยังไม่ควรสรุปข้อมูลแทนครับ"))));
    }

    private Optional<String> confirmationMessage(ToolExecutionResult executionResult) {
        for (var message : executionResult.conversationHistory()) {
            if (!(message instanceof ToolResponseMessage toolResponseMessage)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                try {
                    var root = objectMapper.readTree(response.responseData());
                    var result = root.path("result");
                    if (result.path("requires_confirmation").asBoolean(false)) {
                        String messageText = result.path("message").asText("").trim();
                        return Optional.of(messageText.isBlank()
                                ? "รายการนี้ยังไม่ได้บันทึกครับ พี่สาวยืนยันให้มินิคุงดำเนินการต่อได้ไหมครับ"
                                : messageText);
                    }
                } catch (Exception ignored) {
                    // A non-JSON or unrelated tool result should continue through the model normally.
                }
            }
        }
        return Optional.empty();
    }

    private ChatResponse assistantResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private void copyChatOptions(ChatOptions source, ToolCallingChatOptions.Builder<?> target) {
        if (source == null) {
            return;
        }
        target.model(source.getModel())
                .frequencyPenalty(source.getFrequencyPenalty())
                .maxTokens(source.getMaxTokens())
                .presencePenalty(source.getPresencePenalty())
                .stopSequences(source.getStopSequences())
                .temperature(source.getTemperature())
                .topK(source.getTopK())
                .topP(source.getTopP());
    }

    private boolean hasToolCalls(ChatResponse response) {
        return toolCallCount(response) > 0;
    }

    private int toolCallCount(ChatResponse response) {
        if (response == null) {
            return 0;
        }
        return response.getResults().stream()
                .filter(generation -> generation.getOutput() != null)
                .mapToInt(generation -> generation.getOutput().getToolCalls().size())
                .sum();
    }
}
