package com.minikun.goal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/goals")
public final class GoalController {
    private final GoalService service;

    @Value("${minikun.goal.management.token:${minikun.task.management.token:${minikun.memory.management.token:}}}")
    private String managementToken;

    public GoalController(GoalService service) { this.service = Objects.requireNonNull(service); }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "default") String ownerId,
            @RequestParam(required = false) String status,
            @RequestHeader(value = "X-Minikun-Goal-Token", required = false) String token) {
        authorize(token);
        return service.describeAll(service.list(ownerId, status == null || status.isBlank() ? null : GoalStatus.valueOf(status.toUpperCase())));
    }

    @PostMapping
    public Map<String, Object> create(@RequestParam(defaultValue = "default") String ownerId,
            @RequestBody CreateGoalRequest request,
            @RequestHeader(value = "X-Minikun-Goal-Token", required = false) String token) {
        authorize(token);
        return service.describe(service.create(ownerId, request.conversationId(), request.title(), request.description(),
                request.metric(), request.currentValue(), request.targetValue(), request.progressPercent(),
                request.nextReviewAt(), request.timezone()));
    }

    @PutMapping("/{id}/progress")
    public Map<String, Object> progress(@RequestParam(defaultValue = "default") String ownerId,
            @PathVariable UUID id, @RequestBody ProgressRequest request,
            @RequestHeader(value = "X-Minikun-Goal-Token", required = false) String token) {
        authorize(token);
        return service.describe(service.updateProgress(ownerId, id, request.progressPercent(), request.currentValue(), request.nextReviewAt()));
    }

    @PostMapping("/{id}/complete")
    public Map<String, Object> complete(@RequestParam(defaultValue = "default") String ownerId,
            @PathVariable UUID id, @RequestHeader(value = "X-Minikun-Goal-Token", required = false) String token) {
        authorize(token); return service.describe(service.updateStatus(ownerId, id, GoalStatus.COMPLETED));
    }

    @PostMapping("/{id}/pause")
    public Map<String, Object> pause(@RequestParam(defaultValue = "default") String ownerId,
            @PathVariable UUID id, @RequestHeader(value = "X-Minikun-Goal-Token", required = false) String token) {
        authorize(token); return service.describe(service.updateStatus(ownerId, id, GoalStatus.PAUSED));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@RequestParam(defaultValue = "default") String ownerId,
            @PathVariable UUID id, @RequestHeader(value = "X-Minikun-Goal-Token", required = false) String token) {
        authorize(token); service.updateStatus(ownerId, id, GoalStatus.ARCHIVED); return ResponseEntity.noContent().build();
    }

    private void authorize(String token) {
        if (managementToken != null && !managementToken.isBlank() && !Objects.equals(managementToken, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "goal management token is invalid");
        }
    }

    public record CreateGoalRequest(String conversationId, String title, String description, String metric,
            double currentValue, double targetValue, int progressPercent, String nextReviewAt, String timezone) { }
    public record ProgressRequest(int progressPercent, double currentValue, String nextReviewAt) { }
}
