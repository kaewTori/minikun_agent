package com.minikun.personalloop;

import static com.minikun.personalloop.PersonalLoopModels.*;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Learns utility from explicit recommendation outcomes, separately from factual and style memory. */
public final class OutcomeLearningService {
    private final PersonalLoopStore store;
    private final PersonalTimelineRecorder timeline;
    private final Clock clock;

    public OutcomeLearningService(PersonalLoopStore store, PersonalTimelineRecorder timeline, Clock clock) {
        this.store = Objects.requireNonNull(store); this.timeline = Objects.requireNonNull(timeline);
        this.clock = Objects.requireNonNull(clock);
    }

    public Outcome propose(String ownerId, String conversationId, String category, String recommendation,
            String sourceType, String sourceId) {
        Instant now = clock.instant();
        Outcome value = store.save(new Outcome(UUID.randomUUID(), ownerId, conversationId, category,
                recommendation, sourceType, sourceId, OutcomeStatus.PROPOSED, "", null,
                now, null, null, null));
        timeline.record(ownerId, "OUTCOME_PROPOSED", "OUTCOME", value.id().toString(),
                "คำแนะนำใหม่", recommendation, Map.of("category", value.category()), now);
        return value;
    }

    public Outcome accept(String ownerId, UUID id) { return transition(ownerId, id, OutcomeStatus.ACCEPTED, "", null); }
    public Outcome reject(String ownerId, UUID id, String note) { return transition(ownerId, id, OutcomeStatus.REJECTED, note, null); }
    public Outcome start(String ownerId, UUID id) { return transition(ownerId, id, OutcomeStatus.IN_PROGRESS, "", null); }
    public Outcome complete(String ownerId, UUID id, String note) { return transition(ownerId, id, OutcomeStatus.COMPLETED, note, null); }
    public Outcome abandon(String ownerId, UUID id, String note) { return transition(ownerId, id, OutcomeStatus.ABANDONED, note, null); }

    public Outcome evaluate(String ownerId, UUID id, int score, String note) {
        if (score < 1 || score > 5) throw new IllegalArgumentException("outcome score must be between 1 and 5");
        Outcome current = find(ownerId, id);
        if (current.status() != OutcomeStatus.COMPLETED && current.status() != OutcomeStatus.ABANDONED) {
            throw new IllegalStateException("only completed or abandoned outcomes can be evaluated");
        }
        Instant now = clock.instant();
        Outcome updated = store.save(new Outcome(current.id(), current.ownerId(), current.conversationId(),
                current.category(), current.recommendation(), current.sourceType(), current.sourceId(),
                OutcomeStatus.EVALUATED, note, score, current.createdAt(), current.acceptedAt(),
                current.completedAt(), now));
        timeline.record(ownerId, "OUTCOME_EVALUATED", "OUTCOME", id.toString(), "ประเมินผลคำแนะนำ",
                updated.recommendation(), Map.of("category", updated.category(), "score", score), now);
        return updated;
    }

    public Outcome find(String ownerId, UUID id) {
        return store.outcome(Objects.requireNonNull(id), PersonalLoopModels.owner(ownerId))
                .orElseThrow(() -> new IllegalArgumentException("outcome was not found"));
    }

    public List<Outcome> list(String ownerId, int limit) { return store.outcomes(PersonalLoopModels.owner(ownerId), bounded(limit)); }

    public Map<String, Object> insights(String ownerId) {
        List<Outcome> values = list(ownerId, 500);
        Map<String, List<Outcome>> categories = values.stream().collect(Collectors.groupingBy(Outcome::category));
        List<Map<String, Object>> categoryStats = categories.entrySet().stream().map(entry -> {
            List<Outcome> group = entry.getValue();
            long accepted = group.stream().filter(v -> v.acceptedAt() != null).count();
            long completed = group.stream().filter(v -> v.completedAt() != null).count();
            var scores = group.stream().map(Outcome::score).filter(Objects::nonNull).toList();
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("category", entry.getKey()); stat.put("proposed", group.size());
            stat.put("accepted", accepted); stat.put("completed", completed);
            stat.put("acceptance_rate", group.isEmpty() ? 0d : accepted / (double) group.size());
            stat.put("completion_rate", accepted == 0 ? 0d : completed / (double) accepted);
            stat.put("average_score", scores.isEmpty() ? 0d : scores.stream().mapToInt(Integer::intValue).average().orElse(0));
            return Map.copyOf(stat);
        }).sorted((a, b) -> Double.compare((double) b.get("average_score"), (double) a.get("average_score"))).toList();
        return Map.of("owner_id", PersonalLoopModels.owner(ownerId), "total", values.size(), "categories", categoryStats,
                "learning_basis", "explicit_outcomes_only");
    }

    private Outcome transition(String ownerId, UUID id, OutcomeStatus next, String note, Integer score) {
        Outcome current = find(ownerId, id);
        validate(current.status(), next);
        Instant now = clock.instant();
        Instant accepted = next == OutcomeStatus.ACCEPTED ? now : current.acceptedAt();
        Instant completed = next == OutcomeStatus.COMPLETED || next == OutcomeStatus.ABANDONED ? now : current.completedAt();
        Outcome updated = store.save(new Outcome(current.id(), current.ownerId(), current.conversationId(),
                current.category(), current.recommendation(), current.sourceType(), current.sourceId(), next,
                note == null || note.isBlank() ? current.resultNote() : note, score == null ? current.score() : score,
                current.createdAt(), accepted, completed, current.evaluatedAt()));
        timeline.record(ownerId, "OUTCOME_" + next.name(), "OUTCOME", id.toString(),
                "สถานะคำแนะนำ: " + next.name(), updated.recommendation(), Map.of("category", updated.category()), now);
        return updated;
    }

    private void validate(OutcomeStatus current, OutcomeStatus next) {
        boolean allowed = switch (next) {
            case ACCEPTED, REJECTED -> current == OutcomeStatus.PROPOSED;
            case IN_PROGRESS -> current == OutcomeStatus.ACCEPTED;
            case COMPLETED, ABANDONED -> current == OutcomeStatus.ACCEPTED || current == OutcomeStatus.IN_PROGRESS;
            default -> false;
        };
        if (!allowed) throw new IllegalStateException("invalid outcome transition from " + current + " to " + next);
    }

    private int bounded(int limit) { if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500"); return limit; }
}
