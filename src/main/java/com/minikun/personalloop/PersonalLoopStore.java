package com.minikun.personalloop;

import static com.minikun.personalloop.PersonalLoopModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL persistence with a bounded-process fallback for datasource-free tests. */
public final class PersonalLoopStore {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final TypeReference<List<Map<String, Object>>> MAPS = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Map<UUID, WeeklyReview> reviews = new ConcurrentHashMap<>();
    private final Map<UUID, ReviewProposal> proposals = new ConcurrentHashMap<>();
    private final Map<UUID, Outcome> outcomes = new ConcurrentHashMap<>();
    private final Map<UUID, InboxItem> inbox = new ConcurrentHashMap<>();
    private final Map<UUID, AutomationRecipe> recipes = new ConcurrentHashMap<>();
    private final Map<UUID, AutomationRun> runs = new ConcurrentHashMap<>();
    private final Map<UUID, Incident> incidents = new ConcurrentHashMap<>();
    private final Map<UUID, ExplainabilityTrace> traces = new ConcurrentHashMap<>();
    private final Map<UUID, TimelineEvent> timeline = new ConcurrentHashMap<>();

    public PersonalLoopStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = Objects.requireNonNull(json, "object mapper must not be null");
    }

    public WeeklyReview save(WeeklyReview value) {
        if (jdbc == null) { reviews.put(value.id(), value); return value; }
        jdbc.update("""
                INSERT INTO minikun_weekly_review
                    (id, owner_id, conversation_id, period_start, period_end, status, summary_json, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, summary_json = EXCLUDED.summary_json,
                    completed_at = EXCLUDED.completed_at
                """, value.id(), value.ownerId(), value.conversationId(), ts(value.periodStart()), ts(value.periodEnd()),
                value.status().name(), write(value.summary()), ts(value.createdAt()), ts(value.completedAt()));
        return value;
    }

    public Optional<WeeklyReview> review(UUID id, String ownerId) {
        if (jdbc == null) return Optional.ofNullable(reviews.get(id)).filter(v -> v.ownerId().equals(ownerId));
        return jdbc.query("SELECT * FROM minikun_weekly_review WHERE id = ? AND owner_id = ?", this::review, id, ownerId).stream().findFirst();
    }

    public Optional<WeeklyReview> reviewForPeriod(String ownerId, Instant start, Instant end) {
        if (jdbc == null) return reviews.values().stream().filter(v -> v.ownerId().equals(ownerId)
                && v.periodStart().equals(start) && v.periodEnd().equals(end)).findFirst();
        return jdbc.query("SELECT * FROM minikun_weekly_review WHERE owner_id = ? AND period_start = ? AND period_end = ?",
                this::review, ownerId, ts(start), ts(end)).stream().findFirst();
    }

    public List<WeeklyReview> reviews(String ownerId, int limit) {
        if (jdbc == null) return sorted(reviews.values(), ownerId, WeeklyReview::createdAt, limit);
        return jdbc.query("SELECT * FROM minikun_weekly_review WHERE owner_id = ? ORDER BY created_at DESC LIMIT ?",
                this::review, ownerId, limit);
    }

    public ReviewProposal save(ReviewProposal value) {
        if (jdbc == null) { proposals.put(value.id(), value); return value; }
        jdbc.update("""
                INSERT INTO minikun_review_proposal
                    (id, review_id, owner_id, proposal_type, target_id, title, reason, payload_json, status, created_at, decided_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, payload_json = EXCLUDED.payload_json,
                    decided_at = EXCLUDED.decided_at
                """, value.id(), value.reviewId(), value.ownerId(), value.type(), value.targetId(), value.title(),
                value.reason(), write(value.payload()), value.status().name(), ts(value.createdAt()), ts(value.decidedAt()));
        return value;
    }

    public Optional<ReviewProposal> proposal(UUID id, String ownerId) {
        if (jdbc == null) return Optional.ofNullable(proposals.get(id)).filter(v -> v.ownerId().equals(ownerId));
        return jdbc.query("SELECT * FROM minikun_review_proposal WHERE id = ? AND owner_id = ?", this::proposal, id, ownerId).stream().findFirst();
    }

    public List<ReviewProposal> proposals(UUID reviewId, String ownerId) {
        if (jdbc == null) return proposals.values().stream().filter(v -> v.ownerId().equals(ownerId) && v.reviewId().equals(reviewId))
                .sorted(Comparator.comparing(ReviewProposal::createdAt)).toList();
        return jdbc.query("SELECT * FROM minikun_review_proposal WHERE review_id = ? AND owner_id = ? ORDER BY created_at",
                this::proposal, reviewId, ownerId);
    }

    public Outcome save(Outcome value) {
        if (jdbc == null) { outcomes.put(value.id(), value); return value; }
        jdbc.update("""
                INSERT INTO minikun_outcome
                    (id, owner_id, conversation_id, category, recommendation, source_type, source_id, status,
                     result_note, score, created_at, accepted_at, completed_at, evaluated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, result_note = EXCLUDED.result_note,
                    score = EXCLUDED.score, accepted_at = EXCLUDED.accepted_at,
                    completed_at = EXCLUDED.completed_at, evaluated_at = EXCLUDED.evaluated_at
                """, value.id(), value.ownerId(), value.conversationId(), value.category(), value.recommendation(),
                value.sourceType(), value.sourceId(), value.status().name(), value.resultNote(), value.score(),
                ts(value.createdAt()), ts(value.acceptedAt()), ts(value.completedAt()), ts(value.evaluatedAt()));
        return value;
    }

    public Optional<Outcome> outcome(UUID id, String ownerId) {
        if (jdbc == null) return Optional.ofNullable(outcomes.get(id)).filter(v -> v.ownerId().equals(ownerId));
        return jdbc.query("SELECT * FROM minikun_outcome WHERE id = ? AND owner_id = ?", this::outcome, id, ownerId).stream().findFirst();
    }

    public List<Outcome> outcomes(String ownerId, int limit) {
        if (jdbc == null) return sorted(outcomes.values(), ownerId, Outcome::createdAt, limit);
        return jdbc.query("SELECT * FROM minikun_outcome WHERE owner_id = ? ORDER BY created_at DESC LIMIT ?", this::outcome, ownerId, limit);
    }

    public InboxItem save(InboxItem value) {
        if (jdbc == null) { inbox.put(value.id(), value); return value; }
        jdbc.update("""
                INSERT INTO minikun_inbox_item
                    (id, owner_id, conversation_id, input_type, content, source_ref, classification, confidence,
                     status, preview_json, target_type, target_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET classification = EXCLUDED.classification, confidence = EXCLUDED.confidence,
                    status = EXCLUDED.status, preview_json = EXCLUDED.preview_json, target_type = EXCLUDED.target_type,
                    target_id = EXCLUDED.target_id, updated_at = EXCLUDED.updated_at
                """, value.id(), value.ownerId(), value.conversationId(), value.inputType(), value.content(),
                value.sourceRef(), value.classification().name(), value.confidence(), value.status().name(),
                write(value.preview()), value.targetType(), value.targetId(), ts(value.createdAt()), ts(value.updatedAt()));
        return value;
    }

    public Optional<InboxItem> inbox(UUID id, String ownerId) {
        if (jdbc == null) return Optional.ofNullable(inbox.get(id)).filter(v -> v.ownerId().equals(ownerId));
        return jdbc.query("SELECT * FROM minikun_inbox_item WHERE id = ? AND owner_id = ?", this::inbox, id, ownerId).stream().findFirst();
    }

    public List<InboxItem> inbox(String ownerId, int limit) {
        if (jdbc == null) return sorted(inbox.values(), ownerId, InboxItem::createdAt, limit);
        return jdbc.query("SELECT * FROM minikun_inbox_item WHERE owner_id = ? ORDER BY created_at DESC LIMIT ?", this::inbox, ownerId, limit);
    }

    public AutomationRecipe save(AutomationRecipe value) {
        if (jdbc == null) { recipes.put(value.id(), value); return value; }
        jdbc.update("""
                INSERT INTO minikun_automation_recipe
                    (id, owner_id, name, enabled, trigger_type, trigger_json, action_type, action_json,
                     risk_level, created_at, updated_at, last_triggered_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, enabled = EXCLUDED.enabled,
                    trigger_type = EXCLUDED.trigger_type, trigger_json = EXCLUDED.trigger_json,
                    action_type = EXCLUDED.action_type, action_json = EXCLUDED.action_json,
                    risk_level = EXCLUDED.risk_level, updated_at = EXCLUDED.updated_at,
                    last_triggered_at = EXCLUDED.last_triggered_at
                """, value.id(), value.ownerId(), value.name(), value.enabled(), value.triggerType(), write(value.trigger()),
                value.actionType(), write(value.action()), value.riskLevel().name(), ts(value.createdAt()),
                ts(value.updatedAt()), ts(value.lastTriggeredAt()));
        return value;
    }

    public Optional<AutomationRecipe> recipe(UUID id, String ownerId) {
        if (jdbc == null) return Optional.ofNullable(recipes.get(id)).filter(v -> v.ownerId().equals(ownerId));
        return jdbc.query("SELECT * FROM minikun_automation_recipe WHERE id = ? AND owner_id = ?", this::recipe, id, ownerId).stream().findFirst();
    }

    public List<AutomationRecipe> recipes(String ownerId, boolean enabledOnly) {
        if (jdbc == null) return recipes.values().stream().filter(v -> v.ownerId().equals(ownerId) && (!enabledOnly || v.enabled()))
                .sorted(Comparator.comparing(AutomationRecipe::updatedAt).reversed()).toList();
        String sql = "SELECT * FROM minikun_automation_recipe WHERE owner_id = ?"
                + (enabledOnly ? " AND enabled = TRUE" : "") + " ORDER BY updated_at DESC";
        return jdbc.query(sql, this::recipe, ownerId);
    }

    public AutomationRun save(AutomationRun value) {
        if (jdbc == null) { runs.put(value.id(), value); return value; }
        jdbc.update("""
                INSERT INTO minikun_automation_run
                    (id, recipe_id, owner_id, status, trigger_event_json, action_preview_json,
                     confirmation_required, created_at, decided_at, completed_at, error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, decided_at = EXCLUDED.decided_at,
                    completed_at = EXCLUDED.completed_at, error = EXCLUDED.error
                """, value.id(), value.recipeId(), value.ownerId(), value.status().name(), write(value.triggerEvent()),
                write(value.actionPreview()), value.confirmationRequired(), ts(value.createdAt()), ts(value.decidedAt()),
                ts(value.completedAt()), value.error());
        return value;
    }

    public Optional<AutomationRun> run(UUID id, String ownerId) {
        if (jdbc == null) return Optional.ofNullable(runs.get(id)).filter(v -> v.ownerId().equals(ownerId));
        return jdbc.query("SELECT * FROM minikun_automation_run WHERE id = ? AND owner_id = ?", this::run, id, ownerId).stream().findFirst();
    }

    public List<AutomationRun> runs(String ownerId, int limit) {
        if (jdbc == null) return sorted(runs.values(), ownerId, AutomationRun::createdAt, limit);
        return jdbc.query("SELECT * FROM minikun_automation_run WHERE owner_id = ? ORDER BY created_at DESC LIMIT ?", this::run, ownerId, limit);
    }

    public Incident save(Incident value) {
        if (jdbc == null) { incidents.put(value.id(), value); return value; }
        jdbc.update("""
                INSERT INTO minikun_guardian_incident
                    (id, owner_id, fingerprint, status, severity, summary, probable_cause, findings_json,
                     timeline_json, opened_at, updated_at, resolved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, severity = EXCLUDED.severity,
                    summary = EXCLUDED.summary, probable_cause = EXCLUDED.probable_cause,
                    findings_json = EXCLUDED.findings_json, timeline_json = EXCLUDED.timeline_json,
                    updated_at = EXCLUDED.updated_at, resolved_at = EXCLUDED.resolved_at
                """, value.id(), value.ownerId(), value.fingerprint(), value.status().name(), value.severity(),
                value.summary(), value.probableCause(), write(value.findings()), write(value.timeline()),
                ts(value.openedAt()), ts(value.updatedAt()), ts(value.resolvedAt()));
        return value;
    }

    public Optional<Incident> openIncident(String ownerId, String fingerprint) {
        if (jdbc == null) return incidents.values().stream().filter(v -> v.ownerId().equals(ownerId)
                && v.fingerprint().equals(fingerprint) && v.status() == IncidentStatus.OPEN).findFirst();
        return jdbc.query("SELECT * FROM minikun_guardian_incident WHERE owner_id = ? AND fingerprint = ? AND status = 'OPEN'",
                this::incident, ownerId, fingerprint).stream().findFirst();
    }

    public Optional<Incident> incident(UUID id, String ownerId) {
        if (jdbc == null) return Optional.ofNullable(incidents.get(id)).filter(v -> v.ownerId().equals(ownerId));
        return jdbc.query("SELECT * FROM minikun_guardian_incident WHERE id = ? AND owner_id = ?", this::incident, id, ownerId).stream().findFirst();
    }

    public List<Incident> incidents(String ownerId, int limit) {
        if (jdbc == null) return sorted(incidents.values(), ownerId, Incident::updatedAt, limit);
        return jdbc.query("SELECT * FROM minikun_guardian_incident WHERE owner_id = ? ORDER BY updated_at DESC LIMIT ?", this::incident, ownerId, limit);
    }

    public ExplainabilityTrace save(ExplainabilityTrace value) {
        if (jdbc == null) { traces.put(value.id(), value); return value; }
        jdbc.update("""
                INSERT INTO minikun_explainability_trace
                    (id, owner_id, conversation_id, response_id, summary, sources_json, tools_json, decisions_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (owner_id, response_id) DO NOTHING
                """, value.id(), value.ownerId(), value.conversationId(), value.responseId(), value.summary(),
                write(value.sources()), write(value.tools()), write(value.decisions()), ts(value.createdAt()));
        return value;
    }

    public Optional<ExplainabilityTrace> trace(String ownerId, String responseId) {
        if (jdbc == null) return traces.values().stream().filter(v -> v.ownerId().equals(ownerId) && v.responseId().equals(responseId)).findFirst();
        return jdbc.query("SELECT * FROM minikun_explainability_trace WHERE owner_id = ? AND response_id = ?", this::trace, ownerId, responseId).stream().findFirst();
    }

    public List<ExplainabilityTrace> traces(String ownerId, String conversationId, int limit) {
        if (jdbc == null) return traces.values().stream().filter(v -> v.ownerId().equals(ownerId)
                && (conversationId == null || conversationId.isBlank() || v.conversationId().equals(conversationId)))
                .sorted(Comparator.comparing(ExplainabilityTrace::createdAt).reversed()).limit(limit).toList();
        if (conversationId == null || conversationId.isBlank()) {
            return jdbc.query("SELECT * FROM minikun_explainability_trace WHERE owner_id = ? ORDER BY created_at DESC LIMIT ?", this::trace, ownerId, limit);
        }
        return jdbc.query("SELECT * FROM minikun_explainability_trace WHERE owner_id = ? AND conversation_id = ? ORDER BY created_at DESC LIMIT ?",
                this::trace, ownerId, conversationId, limit);
    }

    public TimelineEvent save(TimelineEvent value) {
        if (jdbc == null) {
            boolean exists = timeline.values().stream().anyMatch(v -> v.ownerId().equals(value.ownerId())
                    && v.eventType().equals(value.eventType()) && v.sourceType().equals(value.sourceType())
                    && v.sourceId().equals(value.sourceId()));
            if (!exists) timeline.put(value.id(), value);
            return value;
        }
        jdbc.update("""
                INSERT INTO minikun_personal_timeline
                    (id, owner_id, event_type, source_type, source_id, title, summary, details_json, occurred_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (owner_id, event_type, source_type, source_id) DO NOTHING
                """, value.id(), value.ownerId(), value.eventType(), value.sourceType(), value.sourceId(),
                value.title(), value.summary(), write(value.details()), ts(value.occurredAt()), ts(value.createdAt()));
        return value;
    }

    public List<TimelineEvent> timeline(String ownerId, int limit) {
        if (jdbc == null) return sorted(timeline.values(), ownerId, TimelineEvent::occurredAt, limit);
        return jdbc.query("SELECT * FROM minikun_personal_timeline WHERE owner_id = ? ORDER BY occurred_at DESC LIMIT ?", this::timeline, ownerId, limit);
    }

    private WeeklyReview review(ResultSet r, int n) throws SQLException { return new WeeklyReview(uuid(r,"id"), r.getString("owner_id"), r.getString("conversation_id"), instant(r,"period_start"), instant(r,"period_end"), ReviewStatus.valueOf(r.getString("status")), readMap(r.getString("summary_json")), instant(r,"created_at"), instant(r,"completed_at")); }
    private ReviewProposal proposal(ResultSet r, int n) throws SQLException { return new ReviewProposal(uuid(r,"id"), uuid(r,"review_id"), r.getString("owner_id"), r.getString("proposal_type"), r.getString("target_id"), r.getString("title"), r.getString("reason"), readMap(r.getString("payload_json")), ProposalStatus.valueOf(r.getString("status")), instant(r,"created_at"), instant(r,"decided_at")); }
    private Outcome outcome(ResultSet r, int n) throws SQLException { Integer score = (Integer) r.getObject("score"); return new Outcome(uuid(r,"id"), r.getString("owner_id"), r.getString("conversation_id"), r.getString("category"), r.getString("recommendation"), r.getString("source_type"), r.getString("source_id"), OutcomeStatus.valueOf(r.getString("status")), r.getString("result_note"), score, instant(r,"created_at"), instant(r,"accepted_at"), instant(r,"completed_at"), instant(r,"evaluated_at")); }
    private InboxItem inbox(ResultSet r, int n) throws SQLException { return new InboxItem(uuid(r,"id"), r.getString("owner_id"), r.getString("conversation_id"), r.getString("input_type"), r.getString("content"), r.getString("source_ref"), InboxClassification.valueOf(r.getString("classification")), r.getDouble("confidence"), InboxStatus.valueOf(r.getString("status")), readMap(r.getString("preview_json")), r.getString("target_type"), r.getString("target_id"), instant(r,"created_at"), instant(r,"updated_at")); }
    private AutomationRecipe recipe(ResultSet r, int n) throws SQLException { return new AutomationRecipe(uuid(r,"id"), r.getString("owner_id"), r.getString("name"), r.getBoolean("enabled"), r.getString("trigger_type"), readMap(r.getString("trigger_json")), r.getString("action_type"), readMap(r.getString("action_json")), RiskLevel.valueOf(r.getString("risk_level")), instant(r,"created_at"), instant(r,"updated_at"), instant(r,"last_triggered_at")); }
    private AutomationRun run(ResultSet r, int n) throws SQLException { return new AutomationRun(uuid(r,"id"), uuid(r,"recipe_id"), r.getString("owner_id"), AutomationRunStatus.valueOf(r.getString("status")), readMap(r.getString("trigger_event_json")), readMap(r.getString("action_preview_json")), r.getBoolean("confirmation_required"), instant(r,"created_at"), instant(r,"decided_at"), instant(r,"completed_at"), r.getString("error")); }
    private Incident incident(ResultSet r, int n) throws SQLException { return new Incident(uuid(r,"id"), r.getString("owner_id"), r.getString("fingerprint"), IncidentStatus.valueOf(r.getString("status")), r.getString("severity"), r.getString("summary"), r.getString("probable_cause"), readMaps(r.getString("findings_json")), readMaps(r.getString("timeline_json")), instant(r,"opened_at"), instant(r,"updated_at"), instant(r,"resolved_at")); }
    private ExplainabilityTrace trace(ResultSet r, int n) throws SQLException { return new ExplainabilityTrace(uuid(r,"id"), r.getString("owner_id"), r.getString("conversation_id"), r.getString("response_id"), r.getString("summary"), readStrings(r.getString("sources_json")), readStrings(r.getString("tools_json")), readMap(r.getString("decisions_json")), instant(r,"created_at")); }
    private TimelineEvent timeline(ResultSet r, int n) throws SQLException { return new TimelineEvent(uuid(r,"id"), r.getString("owner_id"), r.getString("event_type"), r.getString("source_type"), r.getString("source_id"), r.getString("title"), r.getString("summary"), readMap(r.getString("details_json")), instant(r,"occurred_at"), instant(r,"created_at")); }

    private String write(Object value) { try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException("could not serialize personal loop data", e); } }
    private Map<String,Object> readMap(String value) { return read(value, MAP, Map.of()); }
    private List<Map<String,Object>> readMaps(String value) { return read(value, MAPS, List.of()); }
    private List<String> readStrings(String value) { return read(value, STRINGS, List.of()); }
    private <T> T read(String value, TypeReference<T> type, T fallback) { if (value == null || value.isBlank()) return fallback; try { return json.readValue(value, type); } catch (JsonProcessingException e) { throw new IllegalStateException("could not deserialize personal loop data", e); } }
    private UUID uuid(ResultSet r, String column) throws SQLException { Object value = r.getObject(column); return value instanceof UUID id ? id : UUID.fromString(value.toString()); }
    private Instant instant(ResultSet r, String column) throws SQLException { Timestamp value = r.getTimestamp(column); return value == null ? null : value.toInstant(); }
    private Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }

    private <T> List<T> sorted(Iterable<T> values, String ownerId, java.util.function.Function<T, Instant> time, int limit) {
        List<T> result = new ArrayList<>(); values.forEach(result::add);
        return result.stream().filter(v -> ownerOf(v).equals(ownerId)).sorted(Comparator.comparing(time).reversed()).limit(limit).toList();
    }

    private String ownerOf(Object value) {
        return switch (value) {
            case WeeklyReview v -> v.ownerId(); case Outcome v -> v.ownerId(); case InboxItem v -> v.ownerId();
            case AutomationRun v -> v.ownerId(); case Incident v -> v.ownerId(); case TimelineEvent v -> v.ownerId();
            default -> throw new IllegalArgumentException("unsupported owner-scoped value");
        };
    }
}
