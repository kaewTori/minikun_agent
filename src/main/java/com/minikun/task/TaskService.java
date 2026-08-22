package com.minikun.task;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "minikun.task.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(TaskStore.class)
public final class TaskService {
    private final TaskStore store;
    private final Clock clock;

    public TaskService(TaskStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "task store must not be null");
        this.clock = Objects.requireNonNull(clock, "task clock must not be null");
    }

    public PersonalTask create(String ownerId, String conversationId, String kind, String title,
            String description, String dueAt, String timezone, String nextAction, String waitingFor,
            String followUpAt, String parentId) {
        return create(ownerId, conversationId, kind, title, description, dueAt, timezone, nextAction,
                waitingFor, followUpAt, parentId, null);
    }

    public PersonalTask create(String ownerId, String conversationId, String kind, String title,
            String description, String dueAt, String timezone, String nextAction, String waitingFor,
            String followUpAt, String parentId, String goalId) {
        String owner = owner(ownerId);
        ZoneId zone = zone(timezone);
        Instant now = clock.instant();
        Instant due = optionalInstant(dueAt, zone);
        Instant followUp = optionalInstant(followUpAt, zone);
        if (due != null && due.isBefore(now)) {
            throw new IllegalArgumentException("task due_at must not be in the past");
        }
        PersonalTask task = new PersonalTask(
                UUID.randomUUID(), owner, require(conversationId, "conversation id"),
                TaskKind.parse(kind), require(title, "title"), nullable(description), TaskStatus.OPEN,
                optionalUuid(parentId), optionalUuid(goalId), due, zone, nullable(nextAction), nullable(waitingFor), followUp, null,
                now, now, null);
        return store.create(task);
    }

    public List<PersonalTask> list(String ownerId, TaskStatus status) {
        return store.list(owner(ownerId), status);
    }

    public PersonalTask find(String ownerId, UUID id) {
        return store.find(Objects.requireNonNull(id), owner(ownerId))
                .orElseThrow(() -> new IllegalArgumentException("task was not found"));
    }

    public PersonalTask update(String ownerId, UUID id, TaskPatch patch) {
        String owner = owner(ownerId);
        PersonalTask current = store.find(Objects.requireNonNull(id), owner)
                .orElseThrow(() -> new IllegalArgumentException("task was not found"));
        TaskStatus status = patch.status() == null ? current.status() : patch.status();
        if (patch.dueAt() != null && patch.dueAt().isBefore(clock.instant())) {
            throw new IllegalArgumentException("task due_at must not be in the past");
        }
        if (patch.followUpAt() != null && patch.followUpAt().isBefore(clock.instant())) {
            throw new IllegalArgumentException("task follow_up_at must not be in the past");
        }
        Instant completedAt = status == TaskStatus.DONE ? clock.instant() : null;
        PersonalTask updated = new PersonalTask(current.id(), current.ownerId(), current.conversationId(),
                current.kind(), patch.title() == null || patch.title().isBlank() ? current.title() : patch.title().trim(),
                patch.description() == null ? current.description() : patch.description().trim(), status,
                current.parentId(), patch.goalId() == null ? current.goalId() : patch.goalId(), patch.dueAt() == null ? current.dueAt() : patch.dueAt(),
                patch.timezone() == null ? current.timezone() : patch.timezone(),
                patch.nextAction() == null ? current.nextAction() : patch.nextAction().trim(),
                patch.waitingFor() == null ? current.waitingFor() : patch.waitingFor().trim(),
                patch.followUpAt() == null ? current.followUpAt() : patch.followUpAt(),
                current.lastFollowUpAt(), current.createdAt(), clock.instant(), completedAt);
        return store.update(updated);
    }

    public PersonalTask complete(String ownerId, UUID id) {
        return update(ownerId, id, new TaskPatch(null, null, TaskStatus.DONE,
                null, null, null, null, null));
    }

    public boolean cancel(String ownerId, UUID id) {
        return store.delete(Objects.requireNonNull(id), owner(ownerId), clock.instant());
    }

    public List<PersonalTask> dueFollowUps(Instant now) {
        return store.findDueFollowUps(now);
    }

    public void markFollowedUp(PersonalTask task, Instant now) {
        store.markFollowedUp(task, now);
    }

    public Map<String, Object> describe(PersonalTask task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", task.id().toString());
        result.put("owner_id", task.ownerId());
        result.put("kind", task.kind().name());
        result.put("title", task.title());
        result.put("description", task.description());
        result.put("status", task.status().name());
        result.put("due_at", task.dueAt() == null ? null : task.dueAt().toString());
        result.put("timezone", task.timezone().getId());
        result.put("next_action", task.nextAction());
        result.put("goal_id", task.goalId() == null ? null : task.goalId().toString());
        result.put("waiting_for", task.waitingFor());
        result.put("follow_up_at", task.followUpAt() == null ? null : task.followUpAt().toString());
        return result;
    }

    public List<Map<String, Object>> describeAll(List<PersonalTask> tasks) {
        return tasks.stream().map(this::describe).toList();
    }

    private Instant optionalInstant(String value, ZoneId zone) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value.trim()); }
        catch (DateTimeParseException ignored) { }
        try { return java.time.OffsetDateTime.parse(value.trim(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant(); }
        catch (DateTimeParseException ignored) { }
        try { return java.time.LocalDateTime.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(zone).toInstant(); }
        catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("task date must be ISO-8601 date/time");
        }
    }

    private ZoneId zone(String value) {
        try { return ZoneId.of(value == null || value.isBlank() ? "Asia/Bangkok" : value.trim()); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("invalid IANA timezone: " + value); }
    }

    private UUID optionalUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try { return UUID.fromString(value.trim()); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("parent_id must be a valid UUID"); }
    }

    private String owner(String value) {
        String normalized = require(value, "owner id");
        if ("*".equals(normalized)) {
            throw new IllegalArgumentException("owner id must not be wildcard");
        }
        return normalized;
    }

    private String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private String nullable(String value) { return value == null ? "" : value.trim(); }
}
