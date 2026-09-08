package com.minikun.visual;

import com.minikun.tools.Tool;
import com.minikun.tools.ToolCallContext;
import com.minikun.tools.ToolDefinition;
import com.minikun.tools.ToolErrorCode;
import com.minikun.tools.ToolParameter;
import com.minikun.tools.ToolParameterType;
import com.minikun.tools.ToolResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;

/** Native local-image tool shared by model tool calls, Image Studio, and story auto-illustration. */
@Slf4j
public final class ImageGenerationTool implements Tool {
    private static final String PONY_PREFIX = "score_9, score_8_up, score_7_up, ";
    private static final java.util.regex.Pattern ANIMAL_ONLY_TERM = java.util.regex.Pattern.compile(
            "(?<![a-z0-9])(?:cats?|dogs?|animals?)(?![a-z0-9])");
    private static final Set<String> SCHEDULERS = Set.of("dpmpp2m", "euler");
    private static final Set<String> SCHEDULES = Set.of("legacy", "karras");
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "image.generate",
            "Generate one image with the local TinyGrad SDXL runtime and return its local URL. "
                    + "Use only when the user explicitly asks for a generated image or when application "
                    + "orchestration requests a story illustration. Do not use for image search.",
            Map.of(
                    "prompt", new ToolParameter("prompt", ToolParameterType.STRING, true,
                            "English SDXL/Pony prompt describing the image to generate."),
                    "negative_prompt", new ToolParameter("negative_prompt", ToolParameterType.STRING, false,
                            "Optional exclusions such as unwanted artifacts or visual traits."),
                    "face_prompt", new ToolParameter("face_prompt", ToolParameterType.STRING, false,
                            "Optional face-specific prompt for local ADetailer refinement."),
                    "width", new ToolParameter("width", ToolParameterType.INTEGER, false,
                            "Optional width, a multiple of 64 between 64 and 4096."),
                    "height", new ToolParameter("height", ToolParameterType.INTEGER, false,
                            "Optional height, a multiple of 64 between 64 and 4096."),
                    "steps", new ToolParameter("steps", ToolParameterType.INTEGER, false,
                            "Optional diffusion steps from 1 to 200."),
                    "guidance", new ToolParameter("guidance", ToolParameterType.NUMBER, false,
                            "Optional prompt guidance from 0 to 30."),
                    "scheduler", new ToolParameter("scheduler", ToolParameterType.STRING, false,
                            "Optional scheduler: dpmpp2m or euler."),
                    "schedule", new ToolParameter("schedule", ToolParameterType.STRING, false,
                            "Optional noise schedule: legacy or karras."),
                    "seed", new ToolParameter("seed", ToolParameterType.INTEGER, false,
                            "Optional deterministic random seed.")));

    private final StoryIllustrationProvider provider;
    private final GeneratedImageStore store;
    private final ImageGenerationHistoryStore historyStore;
    private final int maximumPromptCharacters;
    private final long maximumPixels;

    public ImageGenerationTool(
            StoryIllustrationProvider provider,
            GeneratedImageStore store,
            int maximumPromptCharacters,
            long maximumPixels) {
        this(provider, store, ImageGenerationHistoryStore.noop(), maximumPromptCharacters, maximumPixels);
    }

    public ImageGenerationTool(
            StoryIllustrationProvider provider,
            GeneratedImageStore store,
            ImageGenerationHistoryStore historyStore,
            int maximumPromptCharacters,
            long maximumPixels) {
        this.provider = Objects.requireNonNull(provider, "image provider must not be null");
        this.store = Objects.requireNonNull(store, "image store must not be null");
        this.historyStore = Objects.requireNonNull(historyStore, "image history store must not be null");
        if (maximumPromptCharacters < 1 || maximumPixels < 64L * 64L) {
            throw new IllegalArgumentException("image tool limits must be positive");
        }
        this.maximumPromptCharacters = maximumPromptCharacters;
        this.maximumPixels = maximumPixels;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    /** The user has authorized local generation and story auto-illustration. */
    @Override
    public boolean requiresExplicitConfirmation(Map<String, Object> arguments) {
        return false;
    }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        try {
            Generation generation = generate(request(arguments), new ImageGenerationScope(
                    context.ownerId(), context.conversationId().value(), "generated", "tool", ""));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", generation.url());
            result.put("provider", generation.provider());
            result.put("content_type", generation.contentType());
            result.put("bytes", generation.bytes());
            result.put("created_at", generation.createdAt().toString());
            result.put("prompt", generation.prompt());
            result.put("negative_prompt", generation.negativePrompt());
            result.put("seed", generation.seed());
            result.put("generation_id", generation.historyId().toString());
            return ToolResult.success(Map.copyOf(result));
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (ImageGenerationException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "local image generation failed");
        }
    }

    public Generation generate(ImageGenerationRequest request) {
        return generate(request, ImageGenerationScope.standalone());
    }

    public Generation generate(ImageGenerationRequest request, ImageGenerationScope scope) {
        ImageGenerationRequest safeRequest = withSeed(validate(request));
        GeneratedImage image = provider.generate(safeRequest);
        String effectivePrompt = image.effectivePrompt() == null
                ? safeRequest.prompt() : image.effectivePrompt();
        String effectiveNegativePrompt = image.effectiveNegativePrompt() == null
                ? safeRequest.negativePrompt() : image.effectiveNegativePrompt();
        GeneratedImageStore.StoredImage stored = store.save(image.bytes());
        UUID historyId = UUID.randomUUID();
        ImageGenerationHistory history = new ImageGenerationHistory(
                historyId, scope.ownerId(), scope.conversationId(), scope.origin(),
                scope.illustrationMode(), scope.sceneTitle(), effectivePrompt,
                effectiveNegativePrompt, safeRequest.seed(), image.provider(),
                safeRequest.width(), safeRequest.height(), safeRequest.steps(), stored.url(), stored.createdAt());
        try {
            historyStore.save(history);
        } catch (RuntimeException exception) {
            log.warn("process=image_generation_history event=save_failed generation_id={} reason={}",
                    historyId, exception.getMessage());
        }
        return new Generation(
                stored.url(), image.provider(), stored.contentType(), stored.bytes(), stored.createdAt(),
                effectivePrompt, effectiveNegativePrompt, safeRequest.seed(), historyId);
    }

    private ImageGenerationRequest withSeed(ImageGenerationRequest request) {
        if (request.seed() != null) return request;
        return new ImageGenerationRequest(
                request.prompt(), request.negativePrompt(), request.facePrompts(), request.width(),
                request.height(), request.steps(), request.guidance(), request.scheduler(), request.schedule(),
                ThreadLocalRandom.current().nextLong(0x1_0000_0000L));
    }

    private ImageGenerationRequest request(Map<String, Object> arguments) {
        Map<String, Object> values = arguments == null ? Map.of() : arguments;
        String facePrompt = text(values, "face_prompt");
        return new ImageGenerationRequest(
                text(values, "prompt"),
                text(values, "negative_prompt"),
                facePrompt.isBlank() ? List.of() : List.of(facePrompt),
                integer(values, "width"),
                integer(values, "height"),
                integer(values, "steps"),
                decimal(values, "guidance"),
                text(values, "scheduler"),
                text(values, "schedule"),
                longInteger(values, "seed"));
    }

    private ImageGenerationRequest validate(ImageGenerationRequest request) {
        Objects.requireNonNull(request, "image generation request must not be null");
        if (request.prompt().isBlank()) {
            throw new IllegalArgumentException("image prompt is required");
        }
        ImageGenerationRequest formatted = ponyRequest(request);
        if (formatted.prompt().length() > maximumPromptCharacters
                || formatted.negativePrompt().length() > maximumPromptCharacters
                || formatted.facePrompts().stream().anyMatch(value -> value == null
                        || value.isBlank() || value.length() > maximumPromptCharacters)) {
            throw new IllegalArgumentException("image prompt exceeds the configured limit");
        }
        validateDimension(formatted.width(), "width");
        validateDimension(formatted.height(), "height");
        if (formatted.width() != null && formatted.height() != null
                && (long) formatted.width() * formatted.height() > maximumPixels) {
            throw new IllegalArgumentException("requested image exceeds the pixel limit");
        }
        if (formatted.steps() != null && (formatted.steps() < 1 || formatted.steps() > 200)) {
            throw new IllegalArgumentException("steps must be between 1 and 200");
        }
        if (formatted.guidance() != null && (!Double.isFinite(formatted.guidance())
                || formatted.guidance() < 0.0 || formatted.guidance() > 30.0)) {
            throw new IllegalArgumentException("guidance must be between 0 and 30");
        }
        choice(formatted.scheduler(), SCHEDULERS, "scheduler");
        choice(formatted.schedule(), SCHEDULES, "schedule");
        if (formatted.seed() != null && (formatted.seed() < 0L || formatted.seed() > 0xffff_ffffL)) {
            throw new IllegalArgumentException("seed must be between 0 and 4294967295");
        }
        return formatted;
    }

    private ImageGenerationRequest ponyRequest(ImageGenerationRequest request) {
        String prompt = request.prompt().strip();
        if (!prompt.toLowerCase(java.util.Locale.ROOT).contains("score_9")) {
            prompt = PONY_PREFIX + prompt;
        }
        String negativePrompt = request.negativePrompt();
        boolean excludesHumans = prompt.toLowerCase(java.util.Locale.ROOT).contains("no humans");
        if (excludesHumans || animalOnly(prompt)) {
            if (!excludesHumans) prompt += ", animal focus, no humans";
            negativePrompt = appendTags(negativePrompt, "human, person, woman, man, girl, boy, 1girl, 1boy");
        }
        return new ImageGenerationRequest(
                prompt, negativePrompt, request.facePrompts(), request.width(), request.height(), request.steps(),
                request.guidance(), request.scheduler(), request.schedule(), request.seed());
    }

    private boolean animalOnly(String prompt) {
        String value = prompt.toLowerCase(java.util.Locale.ROOT);
        boolean animal = ANIMAL_ONLY_TERM.matcher(value).find();
        boolean person = value.contains("1girl") || value.contains("1boy") || value.contains("woman")
                || value.contains(" man") || value.contains(" girl") || value.contains(" boy")
                || value.contains("human") || value.contains("female") || value.contains("male");
        return animal && !person;
    }

    private String appendTags(String existing, String additions) {
        return existing == null || existing.isBlank() ? additions : existing.strip() + ", " + additions;
    }

    private void validateDimension(Integer value, String name) {
        if (value != null && (value < 64 || value > 4096 || value % 64 != 0)) {
            throw new IllegalArgumentException(name + " must be a multiple of 64 between 64 and 4096");
        }
    }

    private void choice(String value, Set<String> allowed, String name) {
        if (!value.isBlank() && !allowed.contains(value)) {
            throw new IllegalArgumentException("unsupported " + name);
        }
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? "" : value.toString().strip();
    }

    private Integer integer(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return Math.toIntExact(number.longValue());
    }

    private Long longInteger(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return number.longValue();
    }

    private Double decimal(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be a number");
        }
        return number.doubleValue();
    }

    public record Generation(
            String url,
            String provider,
            String contentType,
            int bytes,
            Instant createdAt,
            String prompt,
            String negativePrompt,
            long seed,
            UUID historyId) { }
}
