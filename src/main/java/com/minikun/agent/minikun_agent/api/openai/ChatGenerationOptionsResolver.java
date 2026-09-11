package com.minikun.agent.minikun_agent.api.openai;

import java.util.List;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.model.GenerationOptions;
import com.minikun.personality.companion.CompanionMode;

/** Resolves request overrides and application generation profiles into model options. */
final class ChatGenerationOptionsResolver {

    Result resolve(
            ChatCompletionRequest request,
            String userText,
            CompanionMode mode,
            boolean toolOrVisionRequest,
            boolean searchRequested,
            boolean deepResearch,
            boolean creativeConversation,
            TurnPlan turnPlan,
            int configuredMaxTokens,
            double configuredTemperature,
            ChatGenerationProfileSelector profileSelector,
            ChatPerformanceMetrics performanceMetrics) {
        Integer requestedMaxTokens = request.max_completion_tokens() != null
                ? request.max_completion_tokens()
                : request.max_tokens();
        Integer maxTokens = requestedMaxTokens;
        String profile = "request";
        if (maxTokens == null) {
            if (profileSelector == null) {
                maxTokens = configuredMaxTokens;
                profile = "default";
            } else {
                ChatGenerationProfileSelector.Selection selection = profileSelector.select(
                        userText, mode, new ChatGenerationProfileSelector.GenerationSignals(
                                toolOrVisionRequest, searchRequested, deepResearch, creativeConversation),
                        turnPlan, configuredMaxTokens);
                maxTokens = selection.maxTokens();
                profile = selection.profile();
            }
        }
        List<String> stop = request.stop() == null ? List.of() : request.stop();
        Double temperature = request.temperature() != null
                ? request.temperature()
                : configuredTemperature;
        if (performanceMetrics != null) {
            performanceMetrics.generationProfile(profile, maxTokens);
        }
        var reasoning = request.reasoning_effort() == null
                ? GenerationOptions.Reasoning.AUTO : request.reasoning_effort();
        if (reasoning == GenerationOptions.Reasoning.AUTO) {
            reasoning = deepResearch || turnPlan != null && (turnPlan.cooperation().needsExpert()
                    || turnPlan.execution() == TurnPlan.Execution.TOOL_LOOP)
                    ? GenerationOptions.Reasoning.MEDIUM : GenerationOptions.Reasoning.OFF;
        }
        return new Result(new GenerationOptions(temperature, maxTokens, stop, reasoning), profile);
    }

    record Result(GenerationOptions options, String profile) {
    }

    Result resolve(
            ChatCompletionRequest request, String userText, CompanionMode mode,
            boolean toolOrVisionRequest, boolean searchRequested, boolean deepResearch,
            boolean creativeConversation, int configuredMaxTokens, double configuredTemperature,
            ChatGenerationProfileSelector profileSelector, ChatPerformanceMetrics performanceMetrics) {
        return resolve(request, userText, mode, toolOrVisionRequest, searchRequested, deepResearch,
                creativeConversation, null, configuredMaxTokens, configuredTemperature,
                profileSelector, performanceMetrics);
    }
}
