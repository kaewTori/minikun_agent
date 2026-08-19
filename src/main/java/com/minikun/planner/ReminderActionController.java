package com.minikun.planner;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.minikun.agent.minikun_agent.conversation.ConversationId;

/** Explicit reminder responses for notification clients and dashboards. */
@RestController
@ConditionalOnProperty(name = "minikun.planner.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/v1/reminders")
public final class ReminderActionController {
    private final PlannerService planner;

    @Value("${minikun.notification.management.token:${minikun.task.management.token:${minikun.memory.management.token:}}}")
    private String managementToken;

    public ReminderActionController(PlannerService planner) {
        this.planner = Objects.requireNonNull(planner, "planner service must not be null");
    }

    @PostMapping("/{id}/acknowledge")
    public Map<String, Object> acknowledge(
            @PathVariable UUID id,
            @RequestParam(name = "conversation_id") String conversationId,
            @RequestHeader(value = "X-Minikun-Notification-Token", required = false) String token) {
        authorize(token);
        boolean acknowledged = planner.acknowledge(conversation(conversationId), id);
        if (!acknowledged) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "reminder was not found");
        return Map.of("acknowledged", true, "event_id", id.toString());
    }

    @PostMapping("/{id}/snooze")
    public Map<String, Object> snooze(
            @PathVariable UUID id,
            @RequestBody SnoozeRequest request,
            @RequestHeader(value = "X-Minikun-Notification-Token", required = false) String token) {
        authorize(token);
        try {
            PlannerEvent event = planner.snooze(
                    conversation(request.conversationId()), id, request.until(), request.timezone());
            return Map.of("snoozed", true, "event", planner.describe(event));
        } catch (IllegalArgumentException exception) {
            HttpStatus status = exception.getMessage().contains("not found")
                    ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(status, exception.getMessage(), exception);
        }
    }

    private ConversationId conversation(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversation_id is required");
        }
        return new ConversationId(value.trim());
    }

    private void authorize(String token) {
        if (managementToken != null && !managementToken.isBlank() && !Objects.equals(managementToken, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "notification management token is invalid");
        }
    }

    public record SnoozeRequest(String conversationId, String until, String timezone) {}
}
