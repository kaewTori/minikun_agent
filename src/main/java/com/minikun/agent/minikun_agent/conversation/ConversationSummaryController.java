package com.minikun.agent.minikun_agent.conversation;

import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Explicit, owner-scoped controls for inspecting and repairing rolling summaries. */
@RestController
@RequestMapping("/v1/conversations/{conversationId}/summary")
public final class ConversationSummaryController {
    private final ConversationSummaryService summaryService;
    private final ConversationMemoryService memoryService;

    @Value("${minikun.conversation-summary.management.token:${minikun.memory.management.token:}}")
    private String managementToken;

    public ConversationSummaryController(
            ConversationSummaryService summaryService,
            ConversationMemoryService memoryService) {
        this.summaryService = Objects.requireNonNull(summaryService, "summary service must not be null");
        this.memoryService = Objects.requireNonNull(memoryService, "memory service must not be null");
    }

    @GetMapping
    public ConversationSummaryStatus status(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Memory-Token", required = false) String token) {
        authorize(token);
        ConversationId id = new ConversationId(conversationId);
        return summaryService.status(ownerId, id, memoryService.load(id).size());
    }

    @PostMapping("/rebuild")
    public ResponseEntity<ConversationSummaryStatus> rebuild(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Memory-Token", required = false) String token) {
        authorize(token);
        ConversationId id = new ConversationId(conversationId);
        var history = memoryService.load(id);
        if (!summaryService.rebuildNow(ownerId, id, history)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "conversation does not contain enough history to summarize");
        }
        return ResponseEntity.ok(summaryService.status(ownerId, id, history.size()));
    }

    @DeleteMapping
    public Map<String, Object> clear(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Memory-Token", required = false) String token) {
        authorize(token);
        boolean deleted = summaryService.clear(ownerId, new ConversationId(conversationId));
        return Map.of("deleted", deleted, "conversation_id", conversationId, "owner_id", ownerId);
    }

    private void authorize(String token) {
        if (managementToken != null && !managementToken.isBlank()
                && !Objects.equals(managementToken, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "memory management token is invalid");
        }
    }
}
