package com.minikun.personalloop;

import static com.minikun.personalloop.PersonalLoopModels.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Runs small, owner-scoped personal experiments and learns only from explicit results. */
public class PersonalExperimentService {
    private final PersonalLoopStore store;
    private final OutcomeLearningService outcomes;
    private final PersonalTimelineRecorder timeline;
    private final Clock clock;

    public PersonalExperimentService(PersonalLoopStore store, OutcomeLearningService outcomes,
            PersonalTimelineRecorder timeline, Clock clock) {
        this.store = Objects.requireNonNull(store); this.outcomes = Objects.requireNonNull(outcomes);
        this.timeline = Objects.requireNonNull(timeline); this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public ExperimentDetails create(String ownerId, String conversationId, String title, String hypothesis,
            String protocol, String metricName, String metricUnit, MetricDirection direction,
            double baselineValue, double targetValue, int durationDays) {
        String owner = PersonalLoopModels.owner(ownerId);
        UUID experimentId = UUID.randomUUID();
        Outcome outcome = outcomes.propose(owner, conversationId, "personal_experiment", hypothesis,
                "EXPERIMENT", experimentId.toString());
        Instant now = clock.instant();
        PersonalExperiment experiment = store.save(new PersonalExperiment(experimentId, owner, conversationId,
                outcome.id(), title, hypothesis, protocol, metricName, metricUnit, direction, baselineValue,
                targetValue, durationDays, ExperimentStatus.DRAFT, null, null, now, now, null));
        timeline.record(owner, "EXPERIMENT_CREATED", "EXPERIMENT", experiment.id().toString(),
                "สร้างการทดลอง: " + experiment.title(), experiment.hypothesis(),
                Map.of("metric", experiment.metricName(), "duration_days", durationDays), now);
        return details(owner, experiment.id());
    }

    @Transactional
    public ExperimentDetails start(String ownerId, UUID id) {
        PersonalExperiment current = find(ownerId, id);
        require(current.status() == ExperimentStatus.DRAFT, "only a draft experiment can be started");
        outcomes.accept(current.ownerId(), current.outcomeId());
        outcomes.start(current.ownerId(), current.outcomeId());
        Instant now = clock.instant();
        PersonalExperiment updated = copy(current, ExperimentStatus.RUNNING, now,
                now.plus(current.durationDays(), ChronoUnit.DAYS), now, null);
        store.save(updated);
        recordStatus(updated, "EXPERIMENT_STARTED", "เริ่มการทดลอง", now);
        return details(updated.ownerId(), updated.id());
    }

    @Transactional
    public ExperimentDetails pause(String ownerId, UUID id) {
        PersonalExperiment current = find(ownerId, id);
        require(current.status() == ExperimentStatus.RUNNING, "only a running experiment can be paused");
        Instant now = clock.instant();
        PersonalExperiment updated = copy(current, ExperimentStatus.PAUSED, current.startedAt(),
                current.plannedEndAt(), now, null);
        store.save(updated); recordStatus(updated, "EXPERIMENT_PAUSED", "พักการทดลอง", now);
        return details(updated.ownerId(), updated.id());
    }

    @Transactional
    public ExperimentDetails resume(String ownerId, UUID id) {
        PersonalExperiment current = find(ownerId, id);
        require(current.status() == ExperimentStatus.PAUSED, "only a paused experiment can be resumed");
        Instant now = clock.instant();
        PersonalExperiment updated = copy(current, ExperimentStatus.RUNNING, current.startedAt(),
                current.plannedEndAt(), now, null);
        store.save(updated); recordStatus(updated, "EXPERIMENT_RESUMED", "ทำการทดลองต่อ", now);
        return details(updated.ownerId(), updated.id());
    }

    @Transactional
    public ExperimentDetails complete(String ownerId, UUID id, String note) {
        PersonalExperiment current = find(ownerId, id);
        require(current.status() == ExperimentStatus.RUNNING || current.status() == ExperimentStatus.PAUSED,
                "only an active experiment can be completed");
        outcomes.complete(current.ownerId(), current.outcomeId(), note);
        Instant now = clock.instant();
        PersonalExperiment updated = copy(current, ExperimentStatus.COMPLETED, current.startedAt(),
                current.plannedEndAt(), now, now);
        store.save(updated); recordStatus(updated, "EXPERIMENT_COMPLETED", "สรุปการทดลอง", now);
        return details(updated.ownerId(), updated.id());
    }

    @Transactional
    public ExperimentDetails abandon(String ownerId, UUID id, String note) {
        PersonalExperiment current = find(ownerId, id);
        require(current.status() != ExperimentStatus.COMPLETED && current.status() != ExperimentStatus.ABANDONED,
                "finished experiment cannot be abandoned");
        if (current.status() == ExperimentStatus.DRAFT) outcomes.reject(current.ownerId(), current.outcomeId(), note);
        else outcomes.abandon(current.ownerId(), current.outcomeId(), note);
        Instant now = clock.instant();
        PersonalExperiment updated = copy(current, ExperimentStatus.ABANDONED, current.startedAt(),
                current.plannedEndAt(), now, now);
        store.save(updated); recordStatus(updated, "EXPERIMENT_ABANDONED", "หยุดการทดลอง", now);
        return details(updated.ownerId(), updated.id());
    }

    @Transactional
    public ExperimentDetails checkIn(String ownerId, UUID id, double value, String note, Instant observedAt) {
        PersonalExperiment experiment = find(ownerId, id);
        require(experiment.status() == ExperimentStatus.RUNNING, "check-ins require a running experiment");
        Instant now = clock.instant();
        Instant observed = observedAt == null ? now : observedAt;
        if (observed.isBefore(experiment.startedAt())) {
            throw new IllegalArgumentException("check-in cannot be earlier than the experiment start");
        }
        if (observed.isAfter(now.plus(5, ChronoUnit.MINUTES))) {
            throw new IllegalArgumentException("check-in cannot be in the future");
        }
        ExperimentCheckIn checkIn = store.save(new ExperimentCheckIn(UUID.randomUUID(), experiment.id(),
                experiment.ownerId(), value, note, observed, now));
        timeline.record(experiment.ownerId(), "EXPERIMENT_CHECK_IN", "EXPERIMENT_CHECK_IN", checkIn.id().toString(),
                "เช็กอิน: " + experiment.title(), format(value) + " " + experiment.metricUnit(),
                Map.of("experiment_id", experiment.id().toString(), "value", value), observed);
        return details(experiment.ownerId(), experiment.id());
    }

    @Transactional
    public ExperimentDetails evaluate(String ownerId, UUID id, int score, String note) {
        PersonalExperiment experiment = find(ownerId, id);
        require(experiment.status() == ExperimentStatus.COMPLETED,
                "only a completed experiment can be evaluated");
        outcomes.evaluate(experiment.ownerId(), experiment.outcomeId(), score, note);
        return details(experiment.ownerId(), experiment.id());
    }

    public ExperimentDetails details(String ownerId, UUID id) {
        PersonalExperiment experiment = find(ownerId, id);
        List<ExperimentCheckIn> values = store.checkIns(id, experiment.ownerId());
        return new ExperimentDetails(experiment, values, analyze(experiment, values),
                outcomes.find(experiment.ownerId(), experiment.outcomeId()));
    }

    public List<ExperimentOverview> list(String ownerId, ExperimentStatus status, int limit) {
        int maximum = bounded(limit);
        return store.experiments(PersonalLoopModels.owner(ownerId), status, maximum).stream()
                .map(value -> {
                    List<ExperimentCheckIn> values = store.checkIns(value.id(), value.ownerId());
                    Outcome outcome = outcomes.find(value.ownerId(), value.outcomeId());
                    return new ExperimentOverview(value, analyze(value, values), outcome.status(), outcome.score());
                }).toList();
    }

    private PersonalExperiment find(String ownerId, UUID id) {
        return store.experiment(Objects.requireNonNull(id), PersonalLoopModels.owner(ownerId))
                .orElseThrow(() -> new IllegalArgumentException("experiment was not found"));
    }

    private ExperimentAnalysis analyze(PersonalExperiment experiment, List<ExperimentCheckIn> values) {
        double latest = values.isEmpty() ? experiment.baselineValue() : values.getLast().value();
        double desiredChange = experiment.direction() == MetricDirection.INCREASE
                ? experiment.targetValue() - experiment.baselineValue()
                : experiment.baselineValue() - experiment.targetValue();
        double actualChange = experiment.direction() == MetricDirection.INCREASE
                ? latest - experiment.baselineValue() : experiment.baselineValue() - latest;
        int progress = (int) Math.round(Math.max(0, Math.min(100, actualChange / desiredChange * 100)));
        String trend = actualChange > 0 ? (progress >= 100 ? "ON_TARGET" : "IMPROVING")
                : actualChange < 0 ? "MOVING_AWAY" : "UNCHANGED";
        String conclusion = experiment.status() != ExperimentStatus.COMPLETED ? "IN_PROGRESS"
                : progress >= 100 ? "REACHED_TARGET" : actualChange > 0 ? "MADE_PROGRESS"
                : actualChange < 0 ? "MOVED_AWAY" : "NO_CLEAR_CHANGE";
        Instant reference = experiment.completedAt() == null ? clock.instant() : experiment.completedAt();
        long elapsedDays = experiment.startedAt() == null ? 0
                : Math.max(0, Duration.between(experiment.startedAt(), reference).toDays());
        long remainingDays = experiment.plannedEndAt() == null ? experiment.durationDays()
                : Math.max(0, Duration.between(reference, experiment.plannedEndAt()).toDays());
        return new ExperimentAnalysis(round(latest), round(latest - experiment.baselineValue()), progress,
                values.size(), elapsedDays, remainingDays, trend, conclusion);
    }

    private PersonalExperiment copy(PersonalExperiment value, ExperimentStatus status, Instant startedAt,
            Instant plannedEndAt, Instant updatedAt, Instant completedAt) {
        return new PersonalExperiment(value.id(), value.ownerId(), value.conversationId(), value.outcomeId(),
                value.title(), value.hypothesis(), value.protocol(), value.metricName(), value.metricUnit(),
                value.direction(), value.baselineValue(), value.targetValue(), value.durationDays(), status,
                startedAt, plannedEndAt, value.createdAt(), updatedAt, completedAt);
    }

    private void recordStatus(PersonalExperiment experiment, String type, String title, Instant now) {
        timeline.record(experiment.ownerId(), type, "EXPERIMENT", experiment.id().toString(),
                title + ": " + experiment.title(), experiment.hypothesis(),
                Map.of("status", experiment.status().name()), now);
    }

    private int bounded(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return limit;
    }

    private void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private double round(double value) { return Math.round(value * 100d) / 100d; }
    private String format(double value) { return value == Math.rint(value) ? Long.toString(Math.round(value)) : Double.toString(round(value)); }

    public record ExperimentAnalysis(double currentValue, double deltaFromBaseline, int progressPercent,
            int checkInCount, long elapsedDays, long remainingDays, String trend, String conclusion) { }
    public record ExperimentOverview(PersonalExperiment experiment, ExperimentAnalysis analysis,
            OutcomeStatus outcomeStatus, Integer outcomeScore) { }
    public record ExperimentDetails(PersonalExperiment experiment, List<ExperimentCheckIn> checkIns,
            ExperimentAnalysis analysis, Outcome outcome) {
        public ExperimentDetails { checkIns = List.copyOf(checkIns); }
    }
}
