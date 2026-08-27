package com.minikun.agent.minikun_agent.conversation;

import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Deletes all short-term state for one conversation without touching long-term owner memory. */
@RestController
@RequestMapping("/v1/conversations/{conversationId}")
public final class ConversationLifecycleController {
    private final ConversationMemoryService memoryService;
    private final ConversationSummaryService summaryService;

    @Value("${minikun.conversation-summary.management.token:${minikun.memory.management.token:}}")
    private String managementToken;

    public ConversationLifecycleController(
            ConversationMemoryService memoryService,
            ConversationSummaryService summaryService) {
        this.memoryService = Objects.requireNonNull(memoryService);
        this.summaryService = Objects.requireNonNull(summaryService);
    }

    @DeleteMapping
    public Map<String, Object> clear(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Memory-Token", required = false) String token) {
        authorize(token);
        ConversationId id = ConversationId.fromTransport(conversationId);
        memoryService.clear(id);
        boolean summaryDeleted = summaryService.clear(ownerId, id);
        return Map.of(
                "deleted", true,
                "summary_deleted", summaryDeleted,
                "conversation_id", id.value(),
                "owner_id", ownerId);
    }

    private void authorize(String token) {
        if (managementToken != null && !managementToken.isBlank()
                && !Objects.equals(managementToken, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "memory management token is invalid");
        }
    }
}
