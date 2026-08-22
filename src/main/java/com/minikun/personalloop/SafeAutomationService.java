package com.minikun.personalloop;

import static com.minikun.personalloop.PersonalLoopModels.*;

import com.minikun.goal.GoalService;
import com.minikun.notification.NotificationChannel;
import com.minikun.notification.NotificationDispatcher;
import com.minikun.notification.NotificationRequest;
import com.minikun.task.PersonalTask;
import com.minikun.task.TaskService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Allowlisted if/then recipes with server-owned risk and explicit confirmation for state changes. */
public final class SafeAutomationService {
    private static final List<String> TRIGGERS = List.of("INTERVAL", "DAILY_TIME", "TASK_OVERDUE",
            "GOAL_REVIEW_DUE", "GUARDIAN_ISSUE", "INBOX_CLASSIFIED");
    private static final List<String> ACTIONS = List.of("NOTIFY", "GENERATE_WEEKLY_REVIEW", "CREATE_TASK");

    private final PersonalLoopStore store;
    private final PersonalTimelineRecorder timeline;
    private final WeeklyReviewService reviews;
    private final TaskService tasks;
    private final GoalService goals;
    private final NotificationDispatcher notifications;
    private final Clock clock;
    private final ZoneId zone;

    public SafeAutomationService(PersonalLoopStore store, PersonalTimelineRecorder timeline,
            WeeklyReviewService reviews, TaskService tasks, GoalService goals,
            NotificationDispatcher notifications, Clock clock, ZoneId zone) {
        this.store = Objects.requireNonNull(store); this.timeline = Objects.requireNonNull(timeline);
        this.reviews = reviews; this.tasks = tasks; this.goals = goals; this.notifications = notifications;
        this.clock = Objects.requireNonNull(clock); this.zone = Objects.requireNonNull(zone);
    }

    public AutomationRecipe create(String ownerId, String name, String triggerType, Map<String, Object> trigger,
            String actionType, Map<String, Object> action, boolean enabled) {
        String triggerName = allowed(triggerType, TRIGGERS, "trigger");
        String actionName = allowed(actionType, ACTIONS, "action");
        validateTrigger(triggerName, trigger);
        validateAction(actionName, action);
        Instant now = clock.instant();
        AutomationRecipe recipe = store.save(new AutomationRecipe(UUID.randomUUID(), ownerId, name, enabled,
                triggerName, trigger, actionName, action, risk(actionName), now, now, null));
        timeline.record(ownerId, "AUTOMATION_CREATED", "AUTOMATION", recipe.id().toString(),
                "สร้าง automation: " + recipe.name(), recipe.triggerType() + " → " + recipe.actionType(),
                Map.of("risk_level", recipe.riskLevel().name()), now);
        return recipe;
    }

    public AutomationRecipe setEnabled(String ownerId, UUID id, boolean enabled) {
        AutomationRecipe current = findRecipe(ownerId, id);
        AutomationRecipe updated = store.save(new AutomationRecipe(current.id(), current.ownerId(), current.name(),
                enabled, current.triggerType(), current.trigger(), current.actionType(), current.action(),
                current.riskLevel(), current.createdAt(), clock.instant(), current.lastTriggeredAt()));
        timeline.record(ownerId, enabled ? "AUTOMATION_ENABLED" : "AUTOMATION_DISABLED", "AUTOMATION", id.toString(),
                (enabled ? "เปิด" : "ปิด") + " automation: " + current.name(), "", Map.of(), clock.instant());
        return updated;
    }

    public List<AutomationRecipe> recipes(String ownerId) { return store.recipes(PersonalLoopModels.owner(ownerId), false); }
    public List<AutomationRun> runs(String ownerId, int limit) { if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500"); return store.runs(PersonalLoopModels.owner(ownerId), limit); }

    public List<AutomationRun> evaluate(String ownerId) {
        String owner = PersonalLoopModels.owner(ownerId);
        return store.recipes(owner, true).stream().filter(this::triggered).map(this::begin).toList();
    }

    public AutomationRun decide(String ownerId, UUID runId, boolean approve) {
        AutomationRun run = findRun(ownerId, runId);
        if (run.status() != AutomationRunStatus.WAITING_CONFIRMATION) {
            throw new IllegalStateException("automation run is not waiting for confirmation");
        }
        Instant now = clock.instant();
        if (!approve) {
            AutomationRun rejected = store.save(copy(run, AutomationRunStatus.REJECTED, now, now, ""));
            timeline.record(ownerId, "AUTOMATION_REJECTED", "AUTOMATION_RUN", run.id().toString(),
                    "ปฏิเสธ automation run", "", Map.of("recipe_id", run.recipeId().toString()), now);
            return rejected;
        }
        return execute(findRecipe(ownerId, run.recipeId()), run, now);
    }

    private AutomationRun begin(AutomationRecipe recipe) {
        Instant now = clock.instant();
        Map<String, Object> event = triggerEvidence(recipe);
        boolean confirmation = recipe.riskLevel().ordinal() >= RiskLevel.MEDIUM.ordinal();
        AutomationRun run = store.save(new AutomationRun(UUID.randomUUID(), recipe.id(), recipe.ownerId(),
                confirmation ? AutomationRunStatus.WAITING_CONFIRMATION : AutomationRunStatus.SKIPPED,
                event, actionPreview(recipe), confirmation, now, null, null, ""));
        store.save(new AutomationRecipe(recipe.id(), recipe.ownerId(), recipe.name(), recipe.enabled(),
                recipe.triggerType(), recipe.trigger(), recipe.actionType(), recipe.action(), recipe.riskLevel(),
                recipe.createdAt(), now, now));
        timeline.record(recipe.ownerId(), "AUTOMATION_TRIGGERED", "AUTOMATION_RUN", run.id().toString(),
                "Automation ทำงาน: " + recipe.name(), confirmation ? "รอการยืนยัน" : "ดำเนินการอัตโนมัติ",
                Map.of("risk_level", recipe.riskLevel().name()), now);
        return confirmation ? run : execute(recipe, run, now);
    }

    private AutomationRun execute(AutomationRecipe recipe, AutomationRun run, Instant now) {
        try {
            switch (recipe.actionType()) {
                case "NOTIFY" -> notify(recipe, run);
                case "GENERATE_WEEKLY_REVIEW" -> {
                    if (reviews == null) throw new IllegalStateException("weekly review service is unavailable");
                    reviews.generate(recipe.ownerId(), string(recipe.action(), "conversation_id", "automation"),
                            now.minus(Duration.ofDays(7)), now);
                }
                case "CREATE_TASK" -> createTask(recipe);
                default -> throw new IllegalStateException("unsupported automation action");
            }
            AutomationRun completed = store.save(copy(run, AutomationRunStatus.COMPLETED,
                    run.confirmationRequired() ? now : null, now, ""));
            timeline.record(recipe.ownerId(), "AUTOMATION_COMPLETED", "AUTOMATION_RUN", run.id().toString(),
                    "Automation สำเร็จ: " + recipe.name(), recipe.actionType(), Map.of(), now);
            return completed;
        } catch (RuntimeException exception) {
            AutomationRun failed = store.save(copy(run, AutomationRunStatus.FAILED,
                    run.confirmationRequired() ? now : null, now, bounded(exception.getMessage(), 500)));
            timeline.record(recipe.ownerId(), "AUTOMATION_FAILED", "AUTOMATION_RUN", run.id().toString(),
                    "Automation ล้มเหลว: " + recipe.name(), bounded(exception.getMessage(), 300), Map.of(), now);
            return failed;
        }
    }

    private void notify(AutomationRecipe recipe, AutomationRun run) {
        if (notifications == null) throw new IllegalStateException("notification service is unavailable");
        notifications.publish(new NotificationRequest("AUTOMATION", run.id().toString(), NotificationChannel.REMINDER,
                string(recipe.action(), "title", recipe.name()), string(recipe.action(), "message", recipe.name()),
                integer(recipe.action(), "priority", 3), string(recipe.action(), "tags", "robot")));
    }

    private void createTask(AutomationRecipe recipe) {
        if (tasks == null) throw new IllegalStateException("task service is unavailable");
        tasks.create(recipe.ownerId(), string(recipe.action(), "conversation_id", "automation"), "TASK",
                string(recipe.action(), "title", recipe.name()), string(recipe.action(), "description", ""),
                string(recipe.action(), "due_at", ""), string(recipe.action(), "timezone", zone.getId()),
                string(recipe.action(), "next_action", ""), "", string(recipe.action(), "follow_up_at", ""), "",
                string(recipe.action(), "goal_id", ""));
    }

    private boolean triggered(AutomationRecipe recipe) {
        Instant now = clock.instant();
        long cooldown = integer(recipe.trigger(), "cooldown_minutes", 360);
        if (recipe.lastTriggeredAt() != null && now.isBefore(recipe.lastTriggeredAt().plus(Duration.ofMinutes(cooldown)))) return false;
        return switch (recipe.triggerType()) {
            case "INTERVAL" -> recipe.lastTriggeredAt() == null || !now.isBefore(recipe.lastTriggeredAt()
                    .plus(Duration.ofMinutes(integer(recipe.trigger(), "interval_minutes", 60))));
            case "DAILY_TIME" -> {
                LocalTime target = LocalTime.parse(string(recipe.trigger(), "time", "08:00"));
                LocalTime localNow = now.atZone(zone).toLocalTime();
                LocalDate today = now.atZone(zone).toLocalDate();
                boolean notToday = recipe.lastTriggeredAt() == null || !recipe.lastTriggeredAt().atZone(zone).toLocalDate().equals(today);
                yield notToday && !localNow.isBefore(target);
            }
            case "TASK_OVERDUE" -> tasks != null && tasks.list(recipe.ownerId(), null).stream()
                    .filter(PersonalTask::active).anyMatch(task -> task.dueAt() != null && !task.dueAt().isAfter(now));
            case "GOAL_REVIEW_DUE" -> goals != null && !goals.dueForReview(recipe.ownerId(), now, 1).isEmpty();
            case "GUARDIAN_ISSUE" -> store.incidents(recipe.ownerId(), 10).stream()
                    .anyMatch(incident -> incident.status() == IncidentStatus.OPEN);
            case "INBOX_CLASSIFIED" -> {
                String classification = string(recipe.trigger(), "classification", "");
                yield store.inbox(recipe.ownerId(), 100).stream().anyMatch(item -> item.status() == InboxStatus.PREVIEW
                        && (classification.isBlank() || item.classification().name().equalsIgnoreCase(classification))
                        && (recipe.lastTriggeredAt() == null || item.createdAt().isAfter(recipe.lastTriggeredAt())));
            }
            default -> false;
        };
    }

    private Map<String, Object> triggerEvidence(AutomationRecipe recipe) {
        Map<String, Object> evidence = new LinkedHashMap<>(); evidence.put("trigger_type", recipe.triggerType());
        evidence.put("observed_at", clock.instant().toString());
        if ("TASK_OVERDUE".equals(recipe.triggerType()) && tasks != null) {
            long count = tasks.list(recipe.ownerId(), null).stream().filter(PersonalTask::active)
                    .filter(task -> task.dueAt() != null && !task.dueAt().isAfter(clock.instant())).count();
            evidence.put("overdue_tasks", count);
        }
        return Map.copyOf(evidence);
    }

    private Map<String, Object> actionPreview(AutomationRecipe recipe) { return Map.of("action_type", recipe.actionType(), "configuration", recipe.action(), "risk_level", recipe.riskLevel().name()); }
    private RiskLevel risk(String action) { return switch (action) { case "NOTIFY", "GENERATE_WEEKLY_REVIEW" -> RiskLevel.LOW; case "CREATE_TASK" -> RiskLevel.MEDIUM; default -> RiskLevel.CRITICAL; }; }
    private void validateTrigger(String name, Map<String,Object> config) { Map<String,Object> value = config == null ? Map.of() : config; if ("INTERVAL".equals(name) && integer(value,"interval_minutes",60) < 1) throw new IllegalArgumentException("interval_minutes must be positive"); if ("DAILY_TIME".equals(name)) LocalTime.parse(string(value,"time","08:00")); }
    private void validateAction(String name, Map<String,Object> config) { Map<String,Object> value = config == null ? Map.of() : config; if ("NOTIFY".equals(name) && string(value,"message","").isBlank()) throw new IllegalArgumentException("NOTIFY action requires message"); if ("CREATE_TASK".equals(name) && string(value,"title","").isBlank()) throw new IllegalArgumentException("CREATE_TASK action requires title"); }
    private String allowed(String value, List<String> allowed, String field) { String normalized = PersonalLoopModels.text(value, field).toUpperCase(Locale.ROOT); if (!allowed.contains(normalized)) throw new IllegalArgumentException(field + " must be one of " + allowed); return normalized; }
    private AutomationRecipe findRecipe(String ownerId, UUID id) { return store.recipe(Objects.requireNonNull(id), PersonalLoopModels.owner(ownerId)).orElseThrow(() -> new IllegalArgumentException("automation recipe was not found")); }
    private AutomationRun findRun(String ownerId, UUID id) { return store.run(Objects.requireNonNull(id), PersonalLoopModels.owner(ownerId)).orElseThrow(() -> new IllegalArgumentException("automation run was not found")); }
    private AutomationRun copy(AutomationRun v, AutomationRunStatus status, Instant decided, Instant completed, String error) { return new AutomationRun(v.id(), v.recipeId(), v.ownerId(), status, v.triggerEvent(), v.actionPreview(), v.confirmationRequired(), v.createdAt(), decided, completed, error); }
    private String string(Map<String,Object> map, String key, String fallback) { Object value = map == null ? null : map.get(key); return value == null ? fallback : value.toString().trim(); }
    private int integer(Map<String,Object> map, String key, int fallback) { Object value = map == null ? null : map.get(key); return value instanceof Number n ? n.intValue() : value == null ? fallback : Integer.parseInt(value.toString()); }
    private String bounded(String value, int max) { String clean = Objects.requireNonNullElse(value, ""); return clean.length() <= max ? clean : clean.substring(0, max); }
}
