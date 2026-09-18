package com.minikun.agent.minikun_agent.api.openai.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.minikun.weather.DeviceLocation;

public record ChatCompletionRequest(
        String model,
        List<Message> messages,
        String conversation_id,
        Boolean stream,
        Double temperature,
        Integer max_tokens,
        Integer max_completion_tokens,
        List<String> stop,
        String owner_id,
        com.minikun.model.GenerationOptions.Reasoning reasoning_effort,
        String response_mode,
        @JsonProperty("device_location") @JsonInclude(JsonInclude.Include.NON_NULL) DeviceLocation deviceLocation
) {
    public boolean voiceMode() {
        return "voice".equalsIgnoreCase(response_mode);
    }

    public ChatCompletionRequest(String model, List<Message> messages, String conversationId,
            Boolean stream, Double temperature, Integer maxTokens, Integer maxCompletionTokens,
            List<String> stop, String ownerId) {
        this(model, messages, conversationId, stream, temperature, maxTokens, maxCompletionTokens, stop, ownerId, null,
                null, null);
    }

    public ChatCompletionRequest(String model, List<Message> messages, String conversationId,
            Boolean stream, Double temperature, Integer maxTokens, Integer maxCompletionTokens,
            List<String> stop, String ownerId, String responseMode) {
        this(model, messages, conversationId, stream, temperature, maxTokens, maxCompletionTokens, stop, ownerId, null,
                responseMode, null);
    }

    public ChatCompletionRequest(String model, List<Message> messages, String conversationId,
            Boolean stream, Double temperature, Integer maxTokens, Integer maxCompletionTokens) {
        this(model, messages, conversationId, stream, temperature, maxTokens, maxCompletionTokens, null, null, null,
                null, null);
    }

    public ChatCompletionRequest(String model, List<Message> messages, String conversationId,
            Boolean stream, Double temperature, Integer maxTokens, Integer maxCompletionTokens,
            String ownerId) {
        this(model, messages, conversationId, stream, temperature, maxTokens, maxCompletionTokens, null, ownerId, null,
                null, null);
    }

    public ChatCompletionRequest(String model, List<Message> messages, String conversationId,
            Boolean stream, Double temperature, Integer maxTokens, Integer maxCompletionTokens,
            List<String> stop, String ownerId, com.minikun.model.GenerationOptions.Reasoning reasoningEffort,
            String responseMode) {
        this(model, messages, conversationId, stream, temperature, maxTokens, maxCompletionTokens, stop, ownerId,
                reasoningEffort, responseMode, null);
    }

    public ChatCompletionRequest withoutDeviceLocation() {
        return new ChatCompletionRequest(model, messages, conversation_id, stream, temperature, max_tokens,
                max_completion_tokens, stop, owner_id, reasoning_effort, response_mode, null);
    }
}
