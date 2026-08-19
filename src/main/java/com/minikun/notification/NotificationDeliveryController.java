package com.minikun.notification;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only delivery history for dashboards and notification troubleshooting. */
@RestController
@ConditionalOnProperty(name = "minikun.planner.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/v1/notifications")
public final class NotificationDeliveryController {
    private final NotificationDeliveryStore store;

    @Value("${minikun.notification.management.token:${minikun.task.management.token:${minikun.memory.management.token:}}}")
    private String managementToken;

    public NotificationDeliveryController(NotificationDeliveryStore store) {
        this.store = Objects.requireNonNull(store, "notification delivery store must not be null");
    }

    @GetMapping
    public List<NotificationDelivery> list(
            @RequestParam(name = "source_type", required = false) String sourceType,
            @RequestParam(name = "source_id", required = false) String sourceId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = "X-Minikun-Notification-Token", required = false) String token) {
        authorize(token);
        if (limit < 1 || limit > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 500");
        }
        try {
            return store.list(sourceType, sourceId, NotificationDeliveryStatus.parse(status), limit);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    private void authorize(String token) {
        if (managementToken != null && !managementToken.isBlank() && !Objects.equals(managementToken, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "notification management token is invalid");
        }
    }
}
