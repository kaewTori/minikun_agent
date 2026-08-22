package com.minikun.personalloop;

import static com.minikun.personalloop.PersonalLoopModels.TimelineEvent;

import com.minikun.agent.execution.AgentExecutionService;
import com.minikun.goal.GoalService;
import com.minikun.task.TaskService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Unified read model across lifecycle events and existing task, goal, and agent state. */
public final class PersonalTimelineService {
    private final PersonalLoopStore store;
    private final TaskService tasks;
    private final GoalService goals;
    private final AgentExecutionService executions;
    private final Clock clock;

    public PersonalTimelineService(PersonalLoopStore store, TaskService tasks, GoalService goals,
            AgentExecutionService executions, Clock clock) {
        this.store = store; this.tasks = tasks; this.goals = goals; this.executions = executions; this.clock = clock;
    }

    public List<TimelineEvent> list(String ownerId, Instant since, String eventType, int limit) {
        String owner = PersonalLoopModels.owner(ownerId);
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500");
        Map<String, TimelineEvent> events = new LinkedHashMap<>();
        store.timeline(owner, Math.min(500, limit * 3)).forEach(value -> events.put(key(value), value));
        if (tasks != null) tasks.list(owner, null).forEach(task -> {
            Map<String, Object> details = new LinkedHashMap<>(); details.put("status", task.status().name());
            if (task.dueAt() != null) details.put("due_at", task.dueAt().toString());
            TimelineEvent value = synthetic(owner, "TASK_STATE", "TASK", task.id().toString(), task.title(),
                    "สถานะ " + task.status(), details, task.updatedAt()); events.putIfAbsent(key(value), value);
        });
        if (goals != null) goals.list(owner, null).forEach(goal -> {
            TimelineEvent value = synthetic(owner, "GOAL_PROGRESS", "GOAL", goal.id().toString(), goal.title(),
                    "ความคืบหน้า " + goal.progressPercent() + "%", Map.of("status", goal.status().name(),
                            "progress_percent", goal.progressPercent()), goal.updatedAt()); events.putIfAbsent(key(value), value);
        });
        if (executions != null) executions.list(owner, null, 100).forEach(run -> {
            TimelineEvent value = synthetic(owner, "AGENT_RUN", "AGENT_RUN", run.id().toString(), run.objective(),
                    "สถานะ " + run.status(), Map.of("risk_level", run.riskAssessment().level().name(),
                            "current_step", run.currentStep()), run.updatedAt()); events.putIfAbsent(key(value), value);
        });
        String filter = eventType == null ? "" : eventType.trim().toUpperCase(Locale.ROOT);
        return events.values().stream().filter(value -> since == null || !value.occurredAt().isBefore(since))
                .filter(value -> filter.isBlank() || value.eventType().equalsIgnoreCase(filter))
                .sorted(java.util.Comparator.comparing(TimelineEvent::occurredAt).reversed()).limit(limit).toList();
    }

    private TimelineEvent synthetic(String owner, String type, String sourceType, String sourceId, String title,
            String summary, Map<String, Object> details, Instant occurredAt) {
        UUID id = UUID.nameUUIDFromBytes((owner + "|" + type + "|" + sourceType + "|" + sourceId)
                .getBytes(StandardCharsets.UTF_8));
        return new TimelineEvent(id, owner, type, sourceType, sourceId, title, summary, details,
                occurredAt, clock.instant());
    }

    private String key(TimelineEvent value) { return value.eventType() + "|" + value.sourceType() + "|" + value.sourceId(); }
}
