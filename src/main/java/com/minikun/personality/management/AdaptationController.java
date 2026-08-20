package com.minikun.personality.management;

import com.minikun.personality.learning.AdaptationSnapshot;
import com.minikun.personality.learning.AdaptivePreferenceLearningService;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Owner-scoped explainability, feedback, and reset controls for learned response style. */
@RestController
@RequestMapping("/v1/adaptation")
public final class AdaptationController {
    private final AdaptivePreferenceLearningService learning;

    @Value("${minikun.adaptation.management.token:${minikun.memory.management.token:}}")
    private String managementToken;

    public AdaptationController(AdaptivePreferenceLearningService learning) {
        this.learning = Objects.requireNonNull(learning);
    }

    @GetMapping
    public AdaptationSnapshot status(
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Adaptation-Token", required = false) String token) {
        authorize(token);
        return learning.snapshot(ownerId);
    }

    @PostMapping("/feedback")
    public AdaptationSnapshot feedback(
            @RequestBody FeedbackRequest request,
            @RequestHeader(value = "X-Minikun-Adaptation-Token", required = false) String token) {
        authorize(token);
        if (request == null) throw new IllegalArgumentException("adaptation feedback is required");
        return learning.feedback(request.owner_id(), request.dimension(), request.value(), request.positive());
    }

    @DeleteMapping
    public AdaptivePreferenceLearningService.ResetResult reset(
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Adaptation-Token", required = false) String token) {
        authorize(token);
        return learning.reset(ownerId);
    }

    private void authorize(String token) {
        if (managementToken != null && !managementToken.isBlank() && !Objects.equals(managementToken, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "adaptation management token is invalid");
        }
    }

    public record FeedbackRequest(String owner_id, String dimension, String value, boolean positive) {}
}
