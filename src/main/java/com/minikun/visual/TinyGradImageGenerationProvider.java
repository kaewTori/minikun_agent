package com.minikun.visual;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

/** Calls Minikun's local TinyGrad SDXL {@code /generate} service and returns its raw PNG response. */
public final class TinyGradImageGenerationProvider implements StoryIllustrationProvider {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String serviceBaseUrl;
    private final String model;
    private final String defaultNegativePrompt;
    private final int defaultWidth;
    private final int defaultHeight;
    private final int defaultSteps;
    private final double defaultGuidance;
    private final String defaultScheduler;
    private final String defaultSchedule;
    private final int maximumImageBytes;
    private final TinyGradRuntimeAccessCoordinator runtimeAccess;

    public TinyGradImageGenerationProvider(
            RestClient restClient,
            ObjectMapper objectMapper,
            String serviceBaseUrl,
            String model,
            String defaultNegativePrompt,
            int defaultWidth,
            int defaultHeight,
            int defaultSteps,
            double defaultGuidance,
            String defaultScheduler,
            String defaultSchedule,
            int maximumImageBytes) {
        this(restClient, objectMapper, serviceBaseUrl, model, defaultNegativePrompt,
                defaultWidth, defaultHeight, defaultSteps, defaultGuidance,
                defaultScheduler, defaultSchedule, maximumImageBytes,
                new TinyGradRuntimeAccessCoordinator());
    }

    public TinyGradImageGenerationProvider(
            RestClient restClient,
            ObjectMapper objectMapper,
            String serviceBaseUrl,
            String model,
            String defaultNegativePrompt,
            int defaultWidth,
            int defaultHeight,
            int defaultSteps,
            double defaultGuidance,
            String defaultScheduler,
            String defaultSchedule,
            int maximumImageBytes,
            TinyGradRuntimeAccessCoordinator runtimeAccess) {
        this.restClient = Objects.requireNonNull(restClient, "rest client must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.serviceBaseUrl = required(serviceBaseUrl, "service base URL");
        this.model = required(model, "model");
        this.defaultNegativePrompt = optional(defaultNegativePrompt);
        this.defaultWidth = defaultWidth;
        this.defaultHeight = defaultHeight;
        this.defaultSteps = defaultSteps;
        this.defaultGuidance = defaultGuidance;
        this.defaultScheduler = required(defaultScheduler, "scheduler");
        this.defaultSchedule = required(defaultSchedule, "schedule");
        if (maximumImageBytes < 1) {
            throw new IllegalArgumentException("maximum image bytes must be positive");
        }
        this.maximumImageBytes = maximumImageBytes;
        this.runtimeAccess = Objects.requireNonNull(runtimeAccess, "runtime access must not be null");
    }

    @Override
    public GeneratedImage generate(String prompt) {
        return generate(ImageGenerationRequest.promptOnly(prompt));
    }

    @Override
    public String effectiveNegativePrompt(String negativePrompt) {
        return mergeNegativePrompt(negativePrompt, defaultNegativePrompt);
    }

    @Override
    public GeneratedImage generate(ImageGenerationRequest request) {
        Objects.requireNonNull(request, "image generation request must not be null");
        return runtimeAccess.generate(() -> generateExclusively(request));
    }

    private GeneratedImage generateExclusively(ImageGenerationRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prompt", required(request.prompt(), "prompt"));
        String negativePrompt = effectiveNegativePrompt(request.negativePrompt());
        if (!negativePrompt.isBlank()) {
            payload.put("negative_prompt", negativePrompt);
        }
        boolean refineFaces = !request.facePrompts().isEmpty();
        payload.put("adetailer", refineFaces);
        if (refineFaces) {
            payload.put("face_prompts", request.facePrompts());
        }
        payload.put("width", valueOr(request.width(), defaultWidth));
        payload.put("height", valueOr(request.height(), defaultHeight));
        payload.put("steps", valueOr(request.steps(), defaultSteps));
        payload.put("guidance", valueOr(request.guidance(), defaultGuidance));
        payload.put("scheduler", request.scheduler().isBlank() ? defaultScheduler : request.scheduler());
        payload.put("schedule", request.schedule().isBlank() ? defaultSchedule : request.schedule());
        // Preserve the complete story essence across all required CLIP chunks.
        payload.put("long_prompt_mode", "chunk");
        if (request.seed() != null) {
            payload.put("seed", request.seed());
        }
        try {
            byte[] requestBody = objectMapper.writeValueAsBytes(payload);
            byte[] bytes = restClient.post()
                    .uri("/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.IMAGE_PNG)
                    .contentLength(requestBody.length)
                    .body(requestBody)
                    .retrieve()
                    .body(byte[].class);
            if (bytes == null || bytes.length == 0 || bytes.length > maximumImageBytes) {
                throw new ImageGenerationException(ImageGenerationException.Code.INVALID_RESPONSE,
                        "TinyGrad returned an image with an invalid size");
            }
            return new GeneratedImage(bytes, model,
                    Objects.toString(payload.get("prompt"), ""),
                    Objects.toString(payload.get("negative_prompt"), ""));
        } catch (JsonProcessingException exception) {
            throw new ImageGenerationException(ImageGenerationException.Code.INVALID_RESPONSE,
                    "Could not serialize the TinyGrad generation request", exception);
        } catch (RestClientResponseException exception) {
            String hint = switch (exception.getStatusCode().value()) {
                case 401, 403 -> "; check MINIKUN_VISUAL_TINYGRAD_TOKEN against SDXL_SERVER_TOKEN";
                case 429 -> "; another generation may still be running";
                default -> "";
            };
            throw new ImageGenerationException(ImageGenerationException.Code.UPSTREAM_FAILURE,
                    "TinyGrad returned HTTP " + exception.getStatusCode().value() + hint, exception);
        } catch (ResourceAccessException exception) {
            Throwable cause = rootCause(exception);
            if (cause instanceof HttpTimeoutException || cause instanceof HttpConnectTimeoutException
                    || cause instanceof SocketTimeoutException) {
                throw new ImageGenerationException(ImageGenerationException.Code.TIMEOUT,
                        "TinyGrad generation timed out; keep sdxl_server.py running or increase "
                                + "MINIKUN_VISUAL_TINYGRAD_TIMEOUT", exception);
            }
            if (cause instanceof ConnectException) {
                throw new ImageGenerationException(ImageGenerationException.Code.UNAVAILABLE,
                        "TinyGrad is offline at " + serviceBaseUrl
                                + "; start sdxl_server.py --serve 8002 and try again", exception);
            }
            throw new ImageGenerationException(ImageGenerationException.Code.UNAVAILABLE,
                    "TinyGrad is unavailable at " + serviceBaseUrl + "; check the SDXL service log", exception);
        } catch (ImageGenerationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ImageGenerationException("TinyGrad image generation request failed", exception);
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable result = throwable;
        while (result.getCause() != null && result.getCause() != result) {
            result = result.getCause();
        }
        return result;
    }

    private static int valueOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static double valueOr(Double value, double fallback) {
        return value == null ? fallback : value;
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

    private static String mergeNegativePrompt(String priority, String defaults) {
        String first = optional(priority);
        String second = optional(defaults);
        if (first.isBlank()) return second;
        if (second.isBlank()) return first;
        return first + ", " + second;
    }

}
