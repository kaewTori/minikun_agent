package com.minikun.agent.execution;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name = "minikun.agent.execution.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/v1/agent/runs")
public final class AgentExecutionController {
    private final AgentExecutionService executions;
    private final AgentResumeService resumeService;

    @Value("${minikun.agent.execution.management.token:${minikun.task.management.token:${minikun.memory.management.token:}}}")
    private String managementToken;

    public AgentExecutionController(AgentExecutionService executions, AgentResumeService resumeService) {
        this.executions = Objects.requireNonNull(executions, "agent execution service must not be null");
        this.resumeService = Objects.requireNonNull(resumeService, "agent resume service must not be null");
    }

    @GetMapping
    public List<AgentRun> list(
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(name = "conversation_id", required = false) String conversationId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-Minikun-Agent-Token", required = false) String token) {
        authorize(token);
        try {
            return conversationId == null || conversationId.isBlank()
                    ? executions.list(ownerId, AgentRunStatus.parse(status), limit)
                    : executions.list(ownerId, conversationId, AgentRunStatus.parse(status), limit);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/{id}")
    public AgentExecutionService.AgentRunDetails get(
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @PathVariable UUID id,
            @RequestHeader(value = "X-Minikun-Agent-Token", required = false) String token) {
        authorize(token);
        try {
            return executions.details(ownerId, id);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @PostMapping("/{id}/resume")
    public AgentExecutionService.AgentRunDetails resume(
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @PathVariable UUID id,
            @RequestHeader(value = "X-Minikun-Agent-Token", required = false) String token) {
        authorize(token);
        try {
            return resumeService.resume(ownerId, id);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
    }

    private void authorize(String token) {
        if (managementToken != null && !managementToken.isBlank() && !Objects.equals(managementToken, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "agent management token is invalid");
        }
    }
}
