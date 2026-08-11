package com.minikun.tools.springai;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
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
    private final ChatModelProvider chatModelProvider;
    private final ToolCallingManager toolCallingManager;
    private final List<ToolCallback> callbacks;

    public SpringAiToolCallingRuntime(
            ActiveChatModelProvider activeChatModelProvider,
            List<Tool> tools,
            ToolExecutor toolExecutor,
            ObjectMapper objectMapper) {
        this.chatModelProvider = Objects.requireNonNull(
                activeChatModelProvider, "active chat model provider must not be null").get();
        Objects.requireNonNull(tools, "tools must not be null");
        this.callbacks = tools.stream()
                .sorted((left, right) -> left.definition().name().compareTo(right.definition().name()))
                .map(tool -> new SpringAiToolCallback(tool, toolExecutor, objectMapper))
                .map(callback -> (ToolCallback) callback)
                .toList();
        this.toolCallingManager = ToolCallingManager.builder().build();
    }

    public ChatResponse call(Prompt prompt, ConversationId conversationId) {
        Objects.requireNonNull(prompt, "prompt must not be null");
        Objects.requireNonNull(conversationId, "conversation id must not be null");
        if (!chatModelProvider.capabilities().toolCalling()) {
            throw new IllegalStateException(
                    "Chat model provider does not support tool calling: " + chatModelProvider.id());
        }
        ToolCallingChatOptions options = DefaultToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .toolContext(Map.of("conversationId", conversationId.value()))
                .build();
        Prompt currentPrompt = new Prompt(prompt.getInstructions(), options);
        ChatResponse response;
        response = chatModelProvider.chat(currentPrompt);
        if (!hasToolCalls(response)) {
            return response;
        }
        if (toolCallCount(response) != 1) {
            throw new IllegalStateException("multiple tool calls are not supported");
        }
        AssistantMessage.ToolCall toolCall = response.getResults().stream()
                .flatMap(generation -> generation.getOutput().getToolCalls().stream())
                .findFirst()
                .orElseThrow();
        SpringAiToolCallback callback = callbacks.stream()
                .filter(SpringAiToolCallback.class::isInstance)
                .map(SpringAiToolCallback.class::cast)
                .filter(candidate -> candidate.getToolDefinition().name().equals(toolCall.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no callback for tool: " + toolCall.name()));
        callback.setCurrentCallId(toolCall.id());
        ToolExecutionResult executionResult;
        try {
            executionResult = toolCallingManager.executeToolCalls(currentPrompt, response);
        } finally {
            callback.clearCurrentCallId();
        }
        response = chatModelProvider.chat(new Prompt(executionResult.conversationHistory(), options));
        if (hasToolCalls(response)) {
            throw new IllegalStateException("multiple tool continuations are not supported");
        }
        return response;
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