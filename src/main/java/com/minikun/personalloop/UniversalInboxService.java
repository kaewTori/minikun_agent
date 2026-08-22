package com.minikun.personalloop;

import static com.minikun.personalloop.PersonalLoopModels.*;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.goal.GoalService;
import com.minikun.investment.InvestmentService;
import com.minikun.knowledge.PersonalKnowledgeService;
import com.minikun.planner.PlannerService;
import com.minikun.task.TaskService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One preview-first capture point for text, voice transcripts, links, and local knowledge paths. */
public final class UniversalInboxService {
    private final PersonalLoopStore store;
    private final PersonalTimelineRecorder timeline;
    private final TaskService tasks;
    private final GoalService goals;
    private final PlannerService planner;
    private final PersonalKnowledgeService knowledge;
    private final InvestmentService investment;
    private final Clock clock;

    public UniversalInboxService(PersonalLoopStore store, PersonalTimelineRecorder timeline,
            TaskService tasks, GoalService goals, PlannerService planner, PersonalKnowledgeService knowledge,
            InvestmentService investment, Clock clock) {
        this.store = Objects.requireNonNull(store); this.timeline = Objects.requireNonNull(timeline);
        this.tasks = tasks; this.goals = goals; this.planner = planner; this.knowledge = knowledge;
        this.investment = investment; this.clock = Objects.requireNonNull(clock);
    }

    public InboxItem capture(String ownerId, String conversationId, String inputType, String content,
            String sourceRef, String requestedClassification, Map<String, Object> metadata) {
        Classification decision = classify(inputType, content, sourceRef, requestedClassification, metadata);
        Instant now = clock.instant();
        InboxItem item = store.save(new InboxItem(UUID.randomUUID(), ownerId, conversationId, inputType,
                content, sourceRef, decision.classification(), decision.confidence(), InboxStatus.PREVIEW,
                decision.preview(), "", "", now, now));
        timeline.record(ownerId, "INBOX_CAPTURED", "INBOX", item.id().toString(),
                "รับรายการเข้า inbox", decision.classification().name(), Map.of("input_type", item.inputType()), now);
        return item;
    }

    public InboxItem revise(String ownerId, UUID id, String classification, Map<String, Object> preview) {
        InboxItem current = find(ownerId, id);
        if (current.status() != InboxStatus.PREVIEW && current.status() != InboxStatus.FAILED) {
            throw new IllegalStateException("only preview or failed inbox items can be revised");
        }
        InboxClassification type = parseClassification(classification);
        Map<String, Object> updatedPreview = preview == null || preview.isEmpty() ? current.preview() : preview;
        return store.save(copy(current, type, 1d, InboxStatus.PREVIEW, updatedPreview, "", ""));
    }

    public InboxItem commit(String ownerId, UUID id) {
        InboxItem item = find(ownerId, id);
        if (item.status() != InboxStatus.PREVIEW) throw new IllegalStateException("inbox item is not awaiting confirmation");
        try {
            Target target = route(item);
            InboxItem committed = store.save(copy(item, item.classification(), item.confidence(),
                    InboxStatus.COMMITTED, item.preview(), target.type(), target.id()));
            timeline.record(ownerId, "INBOX_COMMITTED", "INBOX", id.toString(), "จัดเก็บรายการจาก inbox แล้ว",
                    item.classification().name(), Map.of("target_type", target.type(), "target_id", target.id()), clock.instant());
            return committed;
        } catch (RuntimeException exception) {
            store.save(copy(item, item.classification(), item.confidence(), InboxStatus.FAILED,
                    withError(item.preview(), exception.getMessage()), "", ""));
            throw exception;
        }
    }

    public InboxItem dismiss(String ownerId, UUID id) {
        InboxItem item = find(ownerId, id);
        if (item.status() == InboxStatus.COMMITTED) throw new IllegalStateException("committed inbox item cannot be dismissed");
        InboxItem dismissed = store.save(copy(item, item.classification(), item.confidence(), InboxStatus.DISMISSED,
                item.preview(), "", ""));
        timeline.record(ownerId, "INBOX_DISMISSED", "INBOX", id.toString(), "ยกเลิกรายการใน inbox",
                item.classification().name(), Map.of(), clock.instant());
        return dismissed;
    }

    public InboxItem find(String ownerId, UUID id) { return store.inbox(Objects.requireNonNull(id), PersonalLoopModels.owner(ownerId)).orElseThrow(() -> new IllegalArgumentException("inbox item was not found")); }
    public List<InboxItem> list(String ownerId, int limit) { if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500"); return store.inbox(PersonalLoopModels.owner(ownerId), limit); }

    private Target route(InboxItem item) {
        Map<String, Object> p = item.preview();
        return switch (item.classification()) {
            case TASK -> {
                if (tasks == null) throw new IllegalStateException("task service is unavailable");
                var task = tasks.create(item.ownerId(), item.conversationId(), string(p, "kind", "TASK"),
                        string(p, "title", title(item.content())), string(p, "description", item.content()),
                        string(p, "due_at", ""), string(p, "timezone", "Asia/Bangkok"),
                        string(p, "next_action", ""), string(p, "waiting_for", ""),
                        string(p, "follow_up_at", ""), "", string(p, "goal_id", ""));
                yield new Target("TASK", task.id().toString());
            }
            case GOAL -> {
                if (goals == null) throw new IllegalStateException("goal service is unavailable");
                var goal = goals.create(item.ownerId(), item.conversationId(), string(p, "title", title(item.content())),
                        string(p, "description", item.content()), string(p, "metric", ""),
                        number(p, "current_value", 0), number(p, "target_value", 0),
                        integer(p, "progress_percent", 0), string(p, "next_review_at", ""),
                        string(p, "timezone", "Asia/Bangkok"));
                yield new Target("GOAL", goal.id().toString());
            }
            case REMINDER -> {
                if (planner == null) throw new IllegalStateException("planner service is unavailable");
                String at = string(p, "at", "");
                if (at.isBlank()) throw new IllegalArgumentException("reminder preview requires an ISO-8601 'at' value");
                var event = planner.create(new ConversationId(item.conversationId()), string(p, "title", title(item.content())),
                        string(p, "note", item.content()), at, string(p, "timezone", "Asia/Bangkok"),
                        integer(p, "remind_before_minutes", 0), string(p, "recurrence", "NONE"));
                yield new Target("REMINDER", event.id().toString());
            }
            case KNOWLEDGE -> {
                String root = string(p, "root", ""); String path = string(p, "path", "");
                if (knowledge != null && !root.isBlank() && !path.isBlank()) {
                    knowledge.index(item.ownerId(), root, path, bool(p, "recursive", false), bool(p, "force", false));
                    yield new Target("KNOWLEDGE", root + ":" + path);
                }
                yield new Target("KNOWLEDGE_CAPTURE", item.id().toString());
            }
            case INVESTMENT_THESIS -> {
                if (investment == null) throw new IllegalStateException("investment service is unavailable");
                var thesis = investment.saveThesis(item.ownerId(), item.conversationId(), null,
                        string(p, "symbol", "UNKNOWN"), string(p, "summary", item.content()),
                        string(p, "invalidation", ""), instant(p.get("next_review_at")));
                yield new Target("INVESTMENT_THESIS", thesis.id().toString());
            }
            case NOTE -> new Target("NOTE", item.id().toString());
        };
    }

    private Classification classify(String inputType, String content, String sourceRef,
            String requested, Map<String, Object> metadata) {
        String normalized = Objects.requireNonNullElse(content, "").toLowerCase(Locale.ROOT);
        Map<String, Object> preview = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        preview.putIfAbsent("title", title(content));
        if (requested != null && !requested.isBlank()) return new Classification(parseClassification(requested), 1d, preview);
        InboxClassification type; double confidence;
        if (containsAny(normalized, "เตือน", "remind", "อย่าลืม") && preview.containsKey("at")) { type = InboxClassification.REMINDER; confidence = .96; }
        else if (containsAny(normalized, "เป้าหมาย", "goal:", "ตั้งเป้า")) { type = InboxClassification.GOAL; confidence = .9; }
        else if (containsAny(normalized, "investment thesis", "สมมติฐานลงทุน", "thesis:", "เงื่อนไขยกเลิกสมมติฐาน")) { type = InboxClassification.INVESTMENT_THESIS; confidence = .9; }
        else if ("LINK".equalsIgnoreCase(inputType) || "FILE".equalsIgnoreCase(inputType)
                || normalized.startsWith("http://") || normalized.startsWith("https://")
                || sourceRef != null && !sourceRef.isBlank()) { type = InboxClassification.KNOWLEDGE; confidence = .88; }
        else if (containsAny(normalized, "todo:", "ต้องทำ", "งาน:", "task:")) { type = InboxClassification.TASK; confidence = .9; }
        else { type = InboxClassification.NOTE; confidence = .55; }
        return new Classification(type, confidence, preview);
    }

    private InboxClassification parseClassification(String value) { try { return InboxClassification.valueOf(PersonalLoopModels.text(value, "classification").toUpperCase(Locale.ROOT)); } catch (RuntimeException e) { throw new IllegalArgumentException("classification must be TASK, REMINDER, GOAL, KNOWLEDGE, INVESTMENT_THESIS, or NOTE"); } }
    private InboxItem copy(InboxItem v, InboxClassification classification, double confidence, InboxStatus status, Map<String,Object> preview, String targetType, String targetId) { return new InboxItem(v.id(), v.ownerId(), v.conversationId(), v.inputType(), v.content(), v.sourceRef(), classification, confidence, status, preview, targetType, targetId, v.createdAt(), clock.instant()); }
    private Map<String,Object> withError(Map<String,Object> source, String error) { Map<String,Object> copy = new LinkedHashMap<>(source); copy.put("error", Objects.requireNonNullElse(error, "routing failed")); return copy; }
    private boolean containsAny(String value, String... terms) { for (String term : terms) if (value.contains(term)) return true; return false; }
    private String title(String value) { String clean = Objects.requireNonNullElse(value, "").strip().replaceAll("\\s+", " "); if (clean.isBlank()) return "Inbox item"; return clean.length() <= 100 ? clean : clean.substring(0, 100); }
    private String string(Map<String,Object> map, String key, String fallback) { Object value = map.get(key); return value == null ? fallback : value.toString().trim(); }
    private int integer(Map<String,Object> map, String key, int fallback) { Object value = map.get(key); return value instanceof Number n ? n.intValue() : value == null ? fallback : Integer.parseInt(value.toString()); }
    private double number(Map<String,Object> map, String key, double fallback) { Object value = map.get(key); return value instanceof Number n ? n.doubleValue() : value == null ? fallback : Double.parseDouble(value.toString()); }
    private boolean bool(Map<String,Object> map, String key, boolean fallback) { Object value = map.get(key); return value instanceof Boolean b ? b : value == null ? fallback : Boolean.parseBoolean(value.toString()); }
    private Instant instant(Object value) { return value == null || value.toString().isBlank() ? null : Instant.parse(value.toString()); }
    private record Classification(InboxClassification classification, double confidence, Map<String,Object> preview) { }
    private record Target(String type, String id) { }
}
