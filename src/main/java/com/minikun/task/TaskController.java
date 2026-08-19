package com.minikun.task;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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

/** Owner-scoped task management API for dashboards and trusted automations. */
@RestController
@ConditionalOnBean(TaskService.class)
@RequestMapping("/v1/tasks")
public final class TaskController {
    private final TaskService service;

    @Value("${minikun.task.management.token:${minikun.memory.management.token:}}")
    private String managementToken;

    public TaskController(TaskService service) {
        this.service = Objects.requireNonNull(service, "task service must not be null");
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(defaultValue = "default") String ownerId,
            @RequestParam(required = false) String status,
            @RequestHeader(value = "X-Minikun-Task-Token", required = false) String token) {
        authorize(token);
        TaskStatus parsed = status == null || status.isBlank() ? null : parseStatus(status);
        return service.describeAll(service.list(ownerId, parsed));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(
            @RequestParam(defaultValue = "default") String ownerId,
            @PathVariable UUID id,
            @RequestHeader(value = "X-Minikun-Task-Token", required = false) String token) {
        authorize(token);
        try {
            return ResponseEntity.ok(service.describe(service.find(ownerId, id)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @RequestParam(defaultValue = "default") String ownerId,
            @PathVariable UUID id,
            @RequestBody TaskPatch patch,
            @RequestHeader(value = "X-Minikun-Task-Token", required = false) String token) {
        authorize(token);
        try {
            return ResponseEntity.ok(service.describe(service.update(ownerId, id, patch)));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<Map<String, Object>> complete(
            @RequestParam(defaultValue = "default") String ownerId,
            @PathVariable UUID id,
            @RequestHeader(value = "X-Minikun-Task-Token", required = false) String token) {
        authorize(token);
        try {
            return ResponseEntity.ok(service.describe(service.complete(ownerId, id)));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(
            @RequestParam(defaultValue = "default") String ownerId,
            @PathVariable UUID id,
            @RequestHeader(value = "X-Minikun-Task-Token", required = false) String token) {
        authorize(token);
        boolean cancelled = service.cancel(ownerId, id);
        return cancelled ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private TaskStatus parseStatus(String value) {
        try {
            return TaskStatus.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    private void authorize(String token) {
        if (managementToken != null && !managementToken.isBlank()
                && !Objects.equals(managementToken, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "task management token is invalid");
        }
    }
}
