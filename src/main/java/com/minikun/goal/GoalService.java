package com.minikun.goal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.minikun.task.PersonalTask;
import com.minikun.task.TaskStatus;
import com.minikun.task.TaskStore;

@Service
public final class GoalService {
    private final GoalStore store;
    private final Clock clock;
    private final TaskStore tasks;

    @Autowired
    public GoalService(GoalStore store, Clock clock, ObjectProvider<TaskStore> tasks) {
        this(store, clock, tasks == null ? null : tasks.getIfAvailable());
    }

    public GoalService(GoalStore store, Clock clock, TaskStore tasks) {
        this.store = Objects.requireNonNull(store, "goal store must not be null");
        this.clock = Objects.requireNonNull(clock, "goal clock must not be null");
        this.tasks = tasks;
    }

    public GoalService(GoalStore store, Clock clock) {
        this(store, clock, (TaskStore) null);
    }

    public PersonalGoal create(String ownerId, String conversationId, String title, String description,
            String metric, double currentValue, double targetValue, int progressPercent,
            String nextReviewAt, String timezone) {
        String owner = owner(ownerId);
        ZoneId zone = zone(timezone);
        Instant now = clock.instant();
        return store.create(new PersonalGoal(UUID.randomUUID(), owner, required(conversationId, "conversation id"),
                required(title, "title"), Objects.requireNonNullElse(description, ""), GoalStatus.ACTIVE,
                progressPercent, metric, currentValue, targetValue, instant(nextReviewAt, zone), zone, now, now, null));
    }

    public List<PersonalGoal> list(String ownerId, GoalStatus status) { return store.list(owner(ownerId), status); }

    /** Reconciles linked task completion into goal progress without touching manually tracked goals. */
    public List<PersonalGoal> syncOpenProgress(String ownerId) {
        if (tasks == null) return list(ownerId, GoalStatus.ACTIVE);
        String owner = owner(ownerId);
        return syncOpenProgress(owner, tasks.list(owner, null));
    }

    /** Reuses an owner-task snapshot so dashboard and recommendation callers do not query the same rows twice. */
    public List<PersonalGoal> syncOpenProgress(String ownerId, List<PersonalTask> ownerTasks) {
        String owner = owner(ownerId);
        if (tasks == null) return list(owner, GoalStatus.ACTIVE);
        Map<UUID, List<PersonalTask>> byGoal = Objects.requireNonNull(ownerTasks, "owner tasks must not be null")
                .stream()
                .filter(task -> owner.equals(task.ownerId()) && task.goalId() != null
                        && task.status() != TaskStatus.CANCELLED)
                .collect(Collectors.groupingBy(PersonalTask::goalId));
        return list(owner, GoalStatus.ACTIVE).stream()
                .map(goal -> syncProgress(goal, byGoal.getOrDefault(goal.id(), List.of())))
                .toList();
    }

    public List<PersonalGoal> dueForReview(String ownerId, Instant now, int limit) {
        Objects.requireNonNull(now, "review time must not be null");
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("goal review limit must be between 1 and 500");
        return store.dueForReview(owner(ownerId), now, limit);
    }

    private PersonalGoal syncProgress(PersonalGoal goal, List<PersonalTask> linked) {
        if (linked.isEmpty()) return goal;
        int completed = (int) linked.stream().filter(task -> task.status() == TaskStatus.DONE).count();
        int progress = Math.min(100, (completed * 100) / linked.size());
        if (progress == goal.progressPercent()) return goal;
        Instant now = clock.instant();
        PersonalGoal updated = new PersonalGoal(goal.id(), goal.ownerId(), goal.conversationId(), goal.title(),
                goal.description(), progress >= 100 ? GoalStatus.COMPLETED : goal.status(), progress,
                goal.metric(), goal.currentValue(), goal.targetValue(), goal.nextReviewAt(), goal.timezone(),
                goal.createdAt(), now, progress >= 100 ? now : null);
        return store.update(updated);
    }

    /** Returns a small bounded context block for agent planning, never raw descriptions. */
    public String activeSummary(String ownerId, int maximumGoals, int maximumCharacters) {
        int goalLimit = Math.max(1, Math.min(maximumGoals, 20));
        int characterLimit = Math.max(200, Math.min(maximumCharacters, 4000));
        StringBuilder summary = new StringBuilder();
        list(ownerId, GoalStatus.ACTIVE).stream().limit(goalLimit).forEach(goal -> {
            if (summary.length() > 0) summary.append("\n");
            summary.append("- ").append(goal.title()).append(": ")
                    .append(goal.progressPercent()).append("%")
                    .append(" (current ").append(goal.currentValue()).append(" / target ")
                    .append(goal.targetValue());
            if (!goal.metric().isBlank()) summary.append(" ").append(goal.metric());
            summary.append(")");
        });
        if (summary.length() > characterLimit) return summary.substring(0, characterLimit);
        return summary.toString();
    }

    public PersonalGoal find(String ownerId, UUID id) {
        return store.find(Objects.requireNonNull(id), owner(ownerId))
                .orElseThrow(() -> new IllegalArgumentException("goal was not found"));
    }

    public PersonalGoal updateProgress(String ownerId, UUID id, int progressPercent,
            double currentValue, String nextReviewAt) {
        PersonalGoal current = find(ownerId, id);
        if (!current.open()) throw new IllegalArgumentException("goal is not open");
        Instant now = clock.instant();
        PersonalGoal updated = new PersonalGoal(current.id(), current.ownerId(), current.conversationId(),
                current.title(), current.description(), progressPercent >= 100 ? GoalStatus.COMPLETED : current.status(),
                progressPercent, current.metric(), currentValue, current.targetValue(),
                nextReviewAt == null ? current.nextReviewAt() : instant(nextReviewAt, current.timezone()),
                current.timezone(), current.createdAt(), now, progressPercent >= 100 ? now : null);
        return store.update(updated);
    }

    public PersonalGoal updateStatus(String ownerId, UUID id, GoalStatus status) {
        PersonalGoal current = find(ownerId, id);
        Objects.requireNonNull(status, "goal status must not be null");
        Instant now = clock.instant();
        return store.update(new PersonalGoal(current.id(), current.ownerId(), current.conversationId(), current.title(),
                current.description(), status, status == GoalStatus.COMPLETED ? 100 : current.progressPercent(),
                current.metric(), current.currentValue(), current.targetValue(), current.nextReviewAt(),
                current.timezone(), current.createdAt(), now, status == GoalStatus.COMPLETED ? now : null));
    }

    public List<Map<String, Object>> describeAll(List<PersonalGoal> goals) { return goals.stream().map(this::describe).toList(); }

    public Map<String, Object> describe(PersonalGoal goal) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", goal.id().toString()); result.put("owner_id", goal.ownerId());
        result.put("title", goal.title()); result.put("description", goal.description());
        result.put("status", goal.status().name()); result.put("progress_percent", goal.progressPercent());
        result.put("metric", goal.metric()); result.put("current_value", goal.currentValue());
        result.put("target_value", goal.targetValue());
        result.put("next_review_at", goal.nextReviewAt() == null ? null : goal.nextReviewAt().toString());
        result.put("timezone", goal.timezone().getId());
        return result;
    }

    private Instant instant(String value, ZoneId zone) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value.trim()); }
        catch (RuntimeException ignored) { }
        try { return java.time.LocalDateTime.parse(value.trim()).atZone(zone).toInstant(); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("goal review time must be ISO-8601"); }
    }
    private ZoneId zone(String value) { try { return ZoneId.of(value == null || value.isBlank() ? "Asia/Bangkok" : value.trim()); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("invalid IANA timezone"); } }
    private String owner(String value) { return required(value, "owner id"); }
    private String required(String value, String field) { if (value == null || value.isBlank() || "*".equals(value)) throw new IllegalArgumentException(field + " must not be blank or wildcard"); return value.trim(); }
}
