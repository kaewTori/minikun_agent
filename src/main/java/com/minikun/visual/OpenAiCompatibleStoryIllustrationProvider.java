package com.minikun.visual;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Calls an OpenAI-compatible {@code /images/generations} endpoint. */
public final class OpenAiCompatibleStoryIllustrationProvider implements StoryIllustrationProvider {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final String size;
    private final String quality;
    private final int maximumImageBytes;

    public OpenAiCompatibleStoryIllustrationProvider(
            RestClient restClient,
            ObjectMapper objectMapper,
            String model,
            String size,
            String quality,
            int maximumImageBytes) {
        this.restClient = Objects.requireNonNull(restClient, "rest client must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.model = required(model, "model");
        this.size = optional(size);
        this.quality = optional(quality);
        if (maximumImageBytes < 1) {
            throw new IllegalArgumentException("maximum image bytes must be positive");
        }
        this.maximumImageBytes = maximumImageBytes;
    }

    @Override
    public GeneratedImage generate(String prompt) {
        String safePrompt = required(prompt, "prompt");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("prompt", safePrompt);
        request.put("n", 1);
        if (!size.isBlank()) {
            request.put("size", size);
        }
        if (!quality.isBlank()) {
            request.put("quality", quality);
        }
        try {
            String body = restClient.post()
                    .uri("/images/generations")
                    .body(request)
                    .retrieve()
                    .body(String.class);
            return parse(body);
        } catch (RestClientResponseException exception) {
            throw new ImageGenerationException(
                    "image provider returned HTTP " + exception.getStatusCode().value(), exception);
        } catch (ImageGenerationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ImageGenerationException("image generation request failed", exception);
        }
    }

    private GeneratedImage parse(String body) {
        try {
            JsonNode item = objectMapper.readTree(body).path("data").path(0);
            String encoded = item.path("b64_json").asText("");
            if (encoded.isBlank()) {
                throw new ImageGenerationException("image provider returned no base64 image");
            }
            long estimatedBytes = (encoded.length() * 3L) / 4L;
            if (estimatedBytes > maximumImageBytes + 3L) {
                throw new ImageGenerationException("generated image is larger than the configured limit");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException exception) {
                throw new ImageGenerationException("image provider returned invalid base64", exception);
            }
            if (bytes.length == 0 || bytes.length > maximumImageBytes) {
                throw new ImageGenerationException("generated image has an invalid size");
            }
            return new GeneratedImage(bytes, item.path("revised_prompt").asText(""), model);
        } catch (ImageGenerationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ImageGenerationException("image provider returned an invalid response", exception);
        }
    }

    private static String required(String value, String name) {
        String result = optional(value);
        if (result.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return result;
    }

    private static String optional(String value) {
        return value == null ? "" : value.strip();
    }
}
