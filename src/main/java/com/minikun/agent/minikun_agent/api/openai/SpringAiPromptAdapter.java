package com.minikun.agent.minikun_agent.api.openai;

import java.util.List;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import com.minikun.diagnostics.DiagnosticsFormatter;
import com.minikun.diagnostics.DiagnosticsPrompt;
import com.minikun.model.ChatModelId;
import com.minikun.model.GenerationOptions;
import com.minikun.pcs.model.PromptMessage;
import com.minikun.vision.VisionInput;

/** Converts provider-neutral prompts into Spring AI requests at the API adapter boundary. */
final class SpringAiPromptAdapter {
    private static final int OLLAMA_TOP_K = 64;
    private static final double OLLAMA_TOP_P = 0.9;
    private static final double OLLAMA_MIN_P = 0.05;
    private static final double OLLAMA_REPEAT_PENALTY = 1.1;

    Prompt adapt(
            com.minikun.pcs.model.Prompt prompt,
            GenerationOptions generationOptions,
            ChatModelId activeModel,
            String configuredModel,
            int ollamaContextSize) {
        List<Message> messages = prompt.messages().stream()
                .map(this::message)
                .toList();
        return new Prompt(messages, chatOptions(
                generationOptions, activeModel, configuredModel, ollamaContextSize));
    }

    Prompt diagnostics(DiagnosticsPrompt prompt, DiagnosticsFormatter formatter) {
        String system = prompt.persona().content()
                + "\n\n[Diagnostics instructions]\n" + prompt.instructions()
                + "\n\n[DiagnosticsSummary]\n" + formatter.format(prompt.summary());
        return new Prompt(List.of(
                new SystemMessage(system),
                new UserMessage(prompt.userRequest())));
    }

    Prompt withVisionMedia(Prompt prompt, VisionInput visionInput) {
        if (visionInput == null || !visionInput.hasImages()) {
            return prompt;
        }
        Prompt multimodalPrompt = prompt.augmentUserMessage(user -> user.mutate()
                .media(visionInput.media())
                .build());
        if (multimodalPrompt.getOptions() instanceof OllamaChatOptions ollamaOptions) {
            return new Prompt(multimodalPrompt.getInstructions(),
                    ollamaOptions.mutate().disableThinking().build());
        }
        return multimodalPrompt;
    }

    private ChatOptions chatOptions(
            GenerationOptions generationOptions,
            ChatModelId activeModel,
            String configuredModel,
            int ollamaContextSize) {
        if (activeModel == ChatModelId.EXISTING) {
            OllamaChatOptions.Builder builder = OllamaChatOptions.builder()
                    .numCtx(ollamaContextSize)
                    .topK(OLLAMA_TOP_K)
                    .topP(OLLAMA_TOP_P)
                    .minP(OLLAMA_MIN_P)
                    .repeatPenalty(OLLAMA_REPEAT_PENALTY)
                    .disableThinking();
            builder
                    .model(configuredModel)
                    .temperature(generationOptions.temperature())
                    .maxTokens(generationOptions.maxTokens());
            if (!generationOptions.stop().isEmpty()) {
                builder.stopSequences(generationOptions.stop());
            }
            return builder.build();
        }
        ChatOptions.Builder<?> builder = ChatOptions.builder();
        builder
                .model(configuredModel)
                .temperature(generationOptions.temperature())
                .maxTokens(generationOptions.maxTokens());
        if (!generationOptions.stop().isEmpty()) {
            builder.stopSequences(generationOptions.stop());
        }
        return builder.build();
    }

    private Message message(PromptMessage message) {
        return switch (message.role()) {
            case SYSTEM -> new SystemMessage(message.content());
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> new AssistantMessage(message.content());
        };
    }
}
