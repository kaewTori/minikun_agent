package com.minikun.agent.minikun_agent.api.openai;

import java.util.List;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.search.internal.ImageIntentDetector;
import com.minikun.vision.VisionInput;

import lombok.extern.slf4j.Slf4j;

/** Turns an attached image into a bounded text query for the existing image-search path. */
@Slf4j
final class VisionSearchQueryService {
    private static final String INSTRUCTION = """
            Inspect the attached image and write one concise English query for image search.
            Include only visible subject, medium or style, composition, palette, lighting, mood, and setting.
            Do not invent names, artists, brands, locations, dates, or source claims.
            Return only the query on one line, without labels, quotes, markdown, or explanation.
            """;
    private final ImageIntentDetector imageIntentDetector = new ImageIntentDetector();
    private final SpringAiPromptAdapter promptAdapter = new SpringAiPromptAdapter();

    String resolve(
            boolean searchEnabled,
            String userQuery,
            VisionInput visionInput,
            ChatModelGateway modelGateway,
            String configuredModel,
            String requestId,
            ConversationId conversationId,
            ChatRequestContext requestContext) {
        if (!searchEnabled || visionInput == null || !visionInput.hasImages()
                || !imageIntentDetector.detectsByImage(userQuery)) {
            return "";
        }
        requestContext.requireRemaining("vision_search");
        try {
            Prompt prompt = new Prompt(
                    List.of(new SystemMessage("You are Minikun's visual search query writer."),
                            new UserMessage(INSTRUCTION)),
                    OllamaChatOptions.builder()
                            .model(configuredModel)
                            .temperature(0.0)
                            .maxTokens(128)
                            .disableThinking()
                            .build());
            ChatResponse response = modelGateway.chat(
                    promptAdapter.withVisionMedia(prompt, visionInput),
                    "vision_to_text_search", requestId, conversationId);
            String query = extract(response);
            log.info("process=vision_to_text_search event=completed query_length={}", query.length());
            return query;
        } catch (RuntimeException exception) {
            log.warn("process=vision_to_text_search event=fallback reason={}",
                    exception.getClass().getSimpleName());
            return "";
        }
    }

    private String extract(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String raw = response.getResult().getOutput().getText();
        if (raw == null) return "";
        String query = raw.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank() && !line.startsWith("```"))
                .findFirst()
                .orElse("")
                .replaceFirst("(?i)^query\\s*:\\s*", "")
                .replaceAll("^[`\"']+|[`\"']+$", "")
                .strip();
        return query.length() > 300 ? query.substring(0, 300).stripTrailing() : query;
    }
}
