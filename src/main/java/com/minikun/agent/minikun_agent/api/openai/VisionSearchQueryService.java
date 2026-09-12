package com.minikun.agent.minikun_agent.api.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ArrayList;

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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_PROFILE_ITEMS = 4;
    private static final int MAX_QUERY_LENGTH = 300;
    private static final String INSTRUCTION = """
            Inspect the attached image and extract a conservative visual profile for image search.
            Return exactly one JSON object with these fields:
            {"subject":"", "attributes":[], "colors":[], "style":[], "setting":[],
             "composition":[], "mood":[], "uncertain":[]}
            Use concise English phrases. Include only what is visibly supported by the image. Keep subject to one
            noun phrase and each array to at most four short phrases. Put guesses in uncertain and never use them
            for the search query. Do not invent names, artists, brands, locations, dates, or source claims.
            You have no tools in this call: never call, simulate, or emit a search or image-generation tool protocol.
            Return JSON only, without markdown fences or explanation.
            """;
    private final ImageIntentDetector imageIntentDetector = new ImageIntentDetector();
    private final SpringAiPromptAdapter promptAdapter = new SpringAiPromptAdapter();

    Result resolve(
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
            return Result.EMPTY;
        }
        requestContext.requireRemaining("vision_search");
        try {
            Prompt prompt = new Prompt(
                    List.of(new SystemMessage("You are Minikun's visual search query writer. You have no tools; return "
                            + "plain text only and never emit tool markup."),
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
            Result result = extract(response);
            log.info("process=vision_to_text_search event=completed query_length={} alternate_count={}",
                    result.query().length(), result.alternateQueries().size());
            return result;
        } catch (RuntimeException exception) {
            log.warn("process=vision_to_text_search event=fallback reason={}",
                    exception.getClass().getSimpleName());
            return Result.EMPTY;
        }
    }

    private Result extract(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return Result.EMPTY;
        }
        String raw = response.getResult().getOutput().getText();
        if (raw == null || raw.isBlank() || looksLikeToolProtocol(raw)) return Result.EMPTY;
        String json = extractJson(raw);
        if (!json.isBlank()) {
            try {
                Result profileResult = fromProfile(OBJECT_MAPPER.readTree(json));
                if (!profileResult.query().isBlank()) {
                    return profileResult;
                }
            } catch (Exception exception) {
                log.debug("process=vision_to_text_search event=profile_parse_failed");
            }
        }
        String query = raw.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank() && !line.startsWith("```"))
                .findFirst()
                .orElse("")
                .replaceFirst("(?i)^query\\s*:\\s*", "")
                .replaceAll("^[`\"']+|[`\"']+$", "")
                .strip();
        if (query.isBlank() || looksLikeUnusableQuery(query)) {
            log.warn("process=vision_to_text_search event=rejected_output reason=non_query_text");
            return Result.EMPTY;
        }
        return new Result(bound(query), List.of());
    }

    private Result fromProfile(JsonNode root) {
        if (root == null || !root.isObject()) {
            return Result.EMPTY;
        }
        String subject = text(root, "subject");
        if (subject.isBlank()) {
            return Result.EMPTY;
        }
        List<String> attributes = values(root, "attributes");
        List<String> colors = values(root, "colors");
        List<String> style = values(root, "style");
        List<String> setting = values(root, "setting");
        List<String> composition = values(root, "composition");
        List<String> mood = values(root, "mood");
        String primary = buildQuery(subject, attributes, colors, style, setting, composition, mood);
        if (primary.isBlank()) {
            return Result.EMPTY;
        }
        List<String> alternates = new ArrayList<>();
        addAlternate(alternates, buildQuery(subject, attributes, colors));
        addAlternate(alternates, buildQuery(subject, style, setting, composition));
        return new Result(primary, alternates);
    }

    private String buildQuery(String subject, List<String>... groups) {
        List<String> parts = new ArrayList<>();
        addPart(parts, subject);
        for (List<String> group : groups) {
            if (group == null) continue;
            for (String value : group) addPart(parts, value);
        }
        return bound(String.join(" ", parts));
    }

    private void addAlternate(List<String> alternates, String candidate) {
        if (!candidate.isBlank() && alternates.stream().noneMatch(candidate::equalsIgnoreCase)) {
            alternates.add(candidate);
        }
    }

    private void addPart(List<String> parts, String value) {
        String normalized = normalizeValue(value);
        if (!normalized.isBlank() && parts.stream().noneMatch(normalized::equalsIgnoreCase)) {
            parts.add(normalized);
        }
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isTextual() ? normalizeValue(value.asText()) : "";
    }

    private List<String> values(JsonNode root, String field) {
        JsonNode values = root.get(field);
        if (values == null || !values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) continue;
            String normalized = normalizeValue(value.asText());
            if (!normalized.isBlank() && result.stream().noneMatch(normalized::equalsIgnoreCase)) {
                result.add(normalized);
            }
            if (result.size() == MAX_PROFILE_ITEMS) break;
        }
        return List.copyOf(result);
    }

    private String normalizeValue(String value) {
        String normalized = Objects.requireNonNullElse(value, "")
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
        if (normalized.length() > 80) normalized = normalized.substring(0, 80).stripTrailing();
        return looksLikeUnusableQuery(normalized) ? "" : normalized;
    }

    private String bound(String value) {
        return value.length() > MAX_QUERY_LENGTH
                ? value.substring(0, MAX_QUERY_LENGTH).stripTrailing() : value;
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1).strip() : "";
    }

    private boolean looksLikeToolProtocol(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("<|tool") || lower.contains("tool*call")
                || lower.contains("googlesearch") || lower.contains("imagesretrieved")
                || lower.contains("imagegeneration") || lower.contains("http://")
                || lower.contains("https://");
    }

    private boolean looksLikeUnusableQuery(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return looksLikeToolProtocol(value) || lower.contains("{") && lower.contains("}");
    }

    record Result(String query, List<String> alternateQueries) {
        static final Result EMPTY = new Result("", List.of());

        Result {
            String normalizedQuery = Objects.requireNonNullElse(query, "").trim();
            query = normalizedQuery;
            alternateQueries = alternateQueries == null ? List.of() : alternateQueries.stream()
                    .filter(value -> value != null && !value.isBlank()
                            && !value.equalsIgnoreCase(normalizedQuery))
                    .map(String::trim)
                    .distinct()
                    .limit(2)
                    .toList();
        }
    }
}
