package com.minikun.personality.feedback;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/chat/feedback")
public final class ChatFeedbackController {
    private final ChatFeedbackService service;
    private final String token;
    public ChatFeedbackController(ChatFeedbackService service,
            @Value("${minikun.adaptation.management.token:${minikun.memory.management.token:}}") String token) {
        this.service = service;
        this.token = token == null ? "" : token.trim();
    }

    @PostMapping
    public ChatFeedback submit(@RequestBody FeedbackRequest request,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String supplied) {
        authorize(supplied);
        if (request == null) throw new IllegalArgumentException("feedback request is required");
        return service.submit(request.owner_id(), request.conversation_id(), request.message_id(),
                request.rating(), request.category(), request.reason());
    }

    @GetMapping
    public List<ChatFeedback> list(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String supplied) {
        authorize(supplied);
        return service.list(ownerId, limit);
    }

    @DeleteMapping
    public void delete(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(name = "conversation_id") String conversationId,
            @RequestParam(name = "message_id") String messageId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String supplied) {
        authorize(supplied);
        service.delete(ownerId, conversationId, messageId);
    }

    private void authorize(String supplied) {
        if (!token.isBlank() && !token.equals(supplied)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "personal token is invalid");
        }
    }

    public record FeedbackRequest(String owner_id, String conversation_id, String message_id,
            String rating, ChatFeedbackCategory category, String reason) {}
}
