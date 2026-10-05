package com.minikun.agent.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.tools.ToolErrorCode;
import com.minikun.tools.ToolResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "minikun.agent.execution.enabled", havingValue = "true", matchIfMissing = true)
public final class AgentExecutionService implements AgentExecutionTracker {
    private final AgentExecutionStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int maxRetries;
    private final int maxSteps;

    public AgentExecutionService(
            AgentExecutionStore store,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${minikun.agent.execution.max-retries:1}") int maxRetries,
            @Value("${minikun.agent.execution.max-tool-steps:12}") int maxSteps) {
        this.store = Objects.requireNonNull(store, "agent execution store must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.maxRetries = Math.max(0, Math.min(maxRetries, 3));
        this.maxSteps = Math.max(1, Math.min(maxSteps, 50));
    }

    @Override
    public Optional<AgentRun> start(String ownerId, String conversationId, AgentPlanDraft plan) {
        return start(ownerId, conversationId, "", plan);
    }

    @Override
    public Optional<AgentRun> start(String ownerId, String conversationId, String responseId, AgentPlanDraft plan) {
        return start(ownerId, conversationId, responseId, plan, maxSteps);
    }

    public Optional<AgentRun> startAction(String ownerId, String conversationId, AgentPlanDraft plan) {
        return start(ownerId, conversationId, "", plan, Math.min(50, Math.max(maxSteps, plan.steps().size() * 6)));
    }

    private Optional<AgentRun> start(String ownerId, String conversationId, String responseId, AgentPlanDraft plan, int stepBudget) {
        Instant now = clock.instant();
        return Optional.of(store.createRun(new AgentRun(UUID.randomUUID(), owner(ownerId),
                require(conversationId, "conversation id"), responseId, plan.objective(), plan.steps(), plan.riskAssessment(), AgentRunStatus.PLANNED,
                0, stepBudget, "", "", now, now, null)));
    }

    @Override
    public AgentExecutionStep beginStep(UUID runId, String toolCallId, String toolName,
            Map<String, Object> arguments) {
        AgentRun run = run(runId);
        if (run.status() == AgentRunStatus.CANCELLED || run.status() == AgentRunStatus.COMPLETED) throw new IllegalStateException("agent run already stopped");
        Optional<AgentExecutionStep> existing = store.findStep(runId, toolCallId);
        Instant now = clock.instant();
        AgentExecutionStep step;
        if (existing.isPresent()) {
            AgentExecutionStep value = existing.get();
            step = new AgentExecutionStep(value.id(), value.runId(), value.stepIndex(), value.toolCallId(),
                    value.toolName(), value.argumentsJson(), AgentStepStatus.RETRYING, value.attempts() + 1,
                    "", "", "", value.startedAt(), now, null);
            store.updateStep(step);
        } else {
            int index = store.listSteps(runId).size() + 1;
            if (index > run.maxSteps()) throw new IllegalStateException("agent tool step limit reached");
            step = new AgentExecutionStep(UUID.randomUUID(), runId, index, require(toolCallId, "tool call id"),
                    require(toolName, "tool name"), json(arguments), AgentStepStatus.RUNNING, 1,
                    "", "", "", now, now, null);
            store.createStep(step);
        }
        updateRun(run, AgentRunStatus.RUNNING, step.stepIndex(), run.summary(), "", null);
        return step;
    }

    @Override
    public void finishStep(UUID runId, String toolCallId, ToolResult result, boolean willRetry) {
        AgentExecutionStep current = store.findStep(runId, toolCallId)
                .orElseThrow(() -> new IllegalArgumentException("agent execution step was not found"));
        Instant now = clock.instant();
        boolean confirmation = result.success() && requiresConfirmation(result.value());
        AgentStepStatus status = willRetry ? AgentStepStatus.RETRYING
                : confirmation ? AgentStepStatus.WAITING_CONFIRMATION
                : result.success() ? AgentStepStatus.COMPLETED : AgentStepStatus.FAILED;
        AgentExecutionStep updated = new AgentExecutionStep(current.id(), current.runId(), current.stepIndex(),
                current.toolCallId(), current.toolName(), current.argumentsJson(), status, current.attempts(),
                result.success() ? json(result.value()) : "",
                result.errorCode() == null ? "" : result.errorCode().name(),
                result.error() == null ? "" : result.error(), current.startedAt(), now,
                willRetry ? null : now);
        store.updateStep(updated);
        if (confirmation) waitingConfirmation(runId, "tool " + current.toolName() + " requires confirmation");
    }

    @Override
    public boolean shouldRetry(ToolResult result, int attempts) {
        return !result.success() && result.errorCode() == ToolErrorCode.EXECUTION_FAILED
                && attempts <= maxRetries;
    }

    @Override
    public void waitingConfirmation(UUID runId, String summary) {
        AgentRun run = run(runId);
        updateRun(run, AgentRunStatus.WAITING_CONFIRMATION, run.currentStep(), summary, "", null);
    }

    @Override
    public void complete(UUID runId, String summary) {
        AgentRun run = run(runId);
        if (run.status() == AgentRunStatus.CANCELLED || run.status() == AgentRunStatus.REVIEW_REQUIRED) return;
        List<AgentExecutionStep> steps = store.listSteps(runId);
        if (steps.stream().anyMatch(step -> step.status() == AgentStepStatus.WAITING_CONFIRMATION)) {
            waitingConfirmation(runId, "waiting for confirmation; remaining plan has not been verified");
            return;
        }
        boolean errors = steps.stream().anyMatch(step -> step.status() == AgentStepStatus.FAILED);
        // Tool success proves that invocation only, not coverage of a natural-language plan.
        updateRun(run, errors ? AgentRunStatus.COMPLETED_WITH_ERRORS : AgentRunStatus.UNVERIFIED,
                run.currentStep(), bounded(summary, 4000), "plan completion has not been independently verified", clock.instant());
    }

    @Override
    public void limitReached(UUID runId, String reason) {
        AgentRun run = run(runId);
        updateRun(run, AgentRunStatus.LIMIT_REACHED, run.currentStep(), run.summary(), bounded(reason, 1000),
                clock.instant());
    }

    @Override
    public void fail(UUID runId, String reason) {
        AgentRun run = run(runId);
        updateRun(run, AgentRunStatus.FAILED, run.currentStep(), run.summary(), bounded(reason, 1000),
                clock.instant());
    }

    public AgentRun find(String ownerId, UUID id) {
        return store.findRun(Objects.requireNonNull(id), owner(ownerId))
                .orElseThrow(() -> new IllegalArgumentException("agent run was not found"));
    }

    public List<AgentRun> list(String ownerId, AgentRunStatus status, int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
        return store.listRuns(owner(ownerId), status, limit);
    }

    public List<AgentRun> list(String ownerId, String conversationId, AgentRunStatus status, int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
        return store.listRuns(owner(ownerId), ConversationId.fromTransport(require(conversationId, "conversation id")).value(), status, limit);
    }

    public List<AgentExecutionStep> steps(String ownerId, UUID runId) {
        find(ownerId, runId);
        return store.listSteps(runId);
    }

    public AgentRunDetails details(String ownerId, UUID runId) {
        return new AgentRunDetails(find(ownerId, runId), steps(ownerId, runId));
    }

    public void markResumed(UUID runId, String summary) {
        complete(runId, summary);
    }

    /** Action worker calls this only after evaluating its persisted observable criteria. */
    public AgentRun actionStatus(String ownerId, UUID runId, AgentRunStatus status, int cursor, String summary) {
        AgentRun run = find(ownerId, runId);
        if (run.status() == AgentRunStatus.CANCELLED && status != AgentRunStatus.CANCELLED) return run;
        return updateRun(run, status, cursor, bounded(summary, 4000),
                status == AgentRunStatus.FAILED || status == AgentRunStatus.REVIEW_REQUIRED ? bounded(summary, 1000) : "",
                status.terminal() ? clock.instant() : null);
    }

    @Override
    public Optional<String> completionNotice(UUID runId) {
        return run(runId).status() == AgentRunStatus.UNVERIFIED
                ? Optional.of("ยังยืนยันไม่ได้ว่าผลลัพธ์ครบทุกข้อของแผนครับ กรุณาดูผลแต่ละขั้นประกอบ")
                : Optional.empty();
    }

    private AgentRun run(UUID id) {
        return store.findRun(id)
                .orElseThrow(() -> new IllegalArgumentException("agent run was not found"));
    }

    public String findRunOwner(UUID id) { return run(id).ownerId(); }

    private AgentRun updateRun(AgentRun run, AgentRunStatus status, int currentStep, String summary,
            String failure, Instant completedAt) {
        return store.updateRun(new AgentRun(run.id(), run.ownerId(), run.conversationId(), run.responseId(), run.objective(),
                run.plannedSteps(), run.riskAssessment(), status, currentStep, run.maxSteps(), summary, failure,
                run.createdAt(), clock.instant(), completedAt));
    }

    private boolean requiresConfirmation(Object value) {
        if (!(value instanceof Map<?, ?> map)) return false;
        Object flag = map.get("requires_confirmation");
        return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize agent execution data", exception);
        }
    }

    private String owner(String value) { return require(value, "owner id"); }
    private String require(String value, String field) {
        if (value == null || value.isBlank() || "*".equals(value)) {
            throw new IllegalArgumentException(field + " must not be blank or wildcard");
        }
        return value.trim();
    }
    private String bounded(String value, int max) {
        String result = Objects.requireNonNullElse(value, "").trim();
        return result.length() <= max ? result : result.substring(0, max);
    }

    public record AgentRunDetails(AgentRun run, List<AgentExecutionStep> steps) {
        public AgentRunDetails { steps = List.copyOf(steps); }
    }
}
