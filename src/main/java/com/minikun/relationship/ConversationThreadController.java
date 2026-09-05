package com.minikun.relationship;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/personal/conversation-threads")
public final class ConversationThreadController {
    private final ConversationThreadService service;
    private final String token;

    public ConversationThreadController(ConversationThreadService service,
            @Value("${minikun.relationship.management.token:${minikun.memory.management.token:}}") String token) {
        this.service = service;
        this.token = token == null ? "" : token.trim();
    }

    @GetMapping
    public List<ConversationThread> list(
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(required = false) ConversationThreadStatus status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String supplied) {
        authorize(supplied);
        return service.list(ownerId, status, limit);
    }

    @PostMapping
    public ConversationThread create(@RequestBody ThreadRequest request,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String supplied) {
        authorize(supplied);
        if (request == null) throw new IllegalArgumentException("thread request is required");
        return service.create(request.owner_id(), request.conversation_id(), request.topic(), request.summary(),
                request.last_decision(), request.unresolved_question(), request.check_in_at(),
                Boolean.TRUE.equals(request.check_in_consent()));
    }

    @PatchMapping("/{id}")
    public ConversationThread update(@PathVariable UUID id, @RequestBody ThreadRequest request,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String supplied) {
        authorize(supplied);
        if (request == null) throw new IllegalArgumentException("thread request is required");
        return service.update(request.owner_id(), id, request.topic(), request.summary(), request.last_decision(),
                request.unresolved_question(), request.status(), request.check_in_at(), request.check_in_consent());
    }

    @DeleteMapping("/{id}")
    public ConversationThread resolve(@PathVariable UUID id,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String supplied) {
        authorize(supplied);
        return service.resolve(ownerId, id);
    }

    @PostMapping("/{id}/feedback")
    public ConversationThread feedback(@PathVariable UUID id, @RequestBody CheckInFeedbackRequest request,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String supplied) {
        authorize(supplied);
        if (request == null) throw new IllegalArgumentException("feedback request is required");
        return service.feedback(request.owner_id(), id, request.feedback(), request.snooze_until());
    }

    private void authorize(String supplied) {
        if (!token.isBlank() && !token.equals(supplied)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "personal token is invalid");
        }
    }

    public record ThreadRequest(String owner_id, String conversation_id, String topic, String summary,
            String last_decision, String unresolved_question, ConversationThreadStatus status,
            Instant check_in_at, Boolean check_in_consent) {}

    public record CheckInFeedbackRequest(
            String owner_id, ConversationCheckInFeedback feedback, Instant snooze_until) {}
}
