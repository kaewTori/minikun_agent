package com.minikun.personalloop;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Shared immutable records for the closed-loop personal agent runtime. */
public final class PersonalLoopModels {
    private PersonalLoopModels() { }

    public enum ReviewStatus { DRAFT, COMPLETED }
    public enum ProposalStatus { PENDING, ACCEPTED, REJECTED, APPLIED, FAILED }
    public enum OutcomeStatus { PROPOSED, ACCEPTED, REJECTED, IN_PROGRESS, COMPLETED, ABANDONED, EVALUATED }
    public enum InboxStatus { PREVIEW, COMMITTED, DISMISSED, FAILED }
    public enum InboxClassification { TASK, REMINDER, GOAL, KNOWLEDGE, INVESTMENT_THESIS, NOTE }
    public enum RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }
    public enum AutomationRunStatus { WAITING_CONFIRMATION, COMPLETED, REJECTED, FAILED, SKIPPED }
    public enum IncidentStatus { OPEN, RESOLVED }
    public enum ExperimentStatus { DRAFT, RUNNING, PAUSED, COMPLETED, ABANDONED }
    public enum MetricDirection { INCREASE, DECREASE }

    public record WeeklyReview(
            UUID id, String ownerId, String conversationId, Instant periodStart, Instant periodEnd,
            ReviewStatus status, Map<String, Object> summary, Instant createdAt, Instant completedAt) {
        public WeeklyReview {
            Objects.requireNonNull(id); ownerId = owner(ownerId); conversationId = text(conversationId, "conversation id");
            Objects.requireNonNull(periodStart); Objects.requireNonNull(periodEnd); Objects.requireNonNull(status);
            if (!periodEnd.isAfter(periodStart)) throw new IllegalArgumentException("review period end must be after start");
            summary = map(summary); Objects.requireNonNull(createdAt);
        }
    }

    public record ReviewProposal(
            UUID id, UUID reviewId, String ownerId, String type, String targetId, String title,
            String reason, Map<String, Object> payload, ProposalStatus status, Instant createdAt, Instant decidedAt) {
        public ReviewProposal {
            Objects.requireNonNull(id); Objects.requireNonNull(reviewId); ownerId = owner(ownerId);
            type = text(type, "proposal type"); targetId = clean(targetId); title = text(title, "proposal title");
            reason = clean(reason); payload = map(payload); Objects.requireNonNull(status); Objects.requireNonNull(createdAt);
        }
    }

    public record Outcome(
            UUID id, String ownerId, String conversationId, String category, String recommendation,
            String sourceType, String sourceId, OutcomeStatus status, String resultNote, Integer score,
            Instant createdAt, Instant acceptedAt, Instant completedAt, Instant evaluatedAt) {
        public Outcome {
            Objects.requireNonNull(id); ownerId = owner(ownerId); conversationId = text(conversationId, "conversation id");
            category = text(category, "outcome category"); recommendation = text(recommendation, "recommendation");
            sourceType = text(sourceType, "outcome source type"); sourceId = clean(sourceId);
            Objects.requireNonNull(status); resultNote = clean(resultNote); Objects.requireNonNull(createdAt);
            if (score != null && (score < 1 || score > 5)) throw new IllegalArgumentException("outcome score must be between 1 and 5");
        }
    }

    public record InboxItem(
            UUID id, String ownerId, String conversationId, String inputType, String content, String sourceRef,
            InboxClassification classification, double confidence, InboxStatus status, Map<String, Object> preview,
            String targetType, String targetId, Instant createdAt, Instant updatedAt) {
        public InboxItem {
            Objects.requireNonNull(id); ownerId = owner(ownerId); conversationId = text(conversationId, "conversation id");
            inputType = text(inputType, "input type").toUpperCase(); content = text(content, "inbox content");
            if (content.length() > 20_000) throw new IllegalArgumentException("inbox content must not exceed 20000 characters");
            sourceRef = clean(sourceRef); Objects.requireNonNull(classification);
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) throw new IllegalArgumentException("inbox confidence must be between 0 and 1");
            Objects.requireNonNull(status); preview = map(preview); targetType = clean(targetType); targetId = clean(targetId);
            Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        }
    }

    public record AutomationRecipe(
            UUID id, String ownerId, String name, boolean enabled, String triggerType, Map<String, Object> trigger,
            String actionType, Map<String, Object> action, RiskLevel riskLevel, Instant createdAt, Instant updatedAt,
            Instant lastTriggeredAt) {
        public AutomationRecipe {
            Objects.requireNonNull(id); ownerId = owner(ownerId); name = text(name, "recipe name");
            triggerType = text(triggerType, "trigger type").toUpperCase(); trigger = map(trigger);
            actionType = text(actionType, "action type").toUpperCase(); action = map(action);
            Objects.requireNonNull(riskLevel); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        }
    }

    public record AutomationRun(
            UUID id, UUID recipeId, String ownerId, AutomationRunStatus status, Map<String, Object> triggerEvent,
            Map<String, Object> actionPreview, boolean confirmationRequired, Instant createdAt, Instant decidedAt,
            Instant completedAt, String error) {
        public AutomationRun {
            Objects.requireNonNull(id); Objects.requireNonNull(recipeId); ownerId = owner(ownerId);
            Objects.requireNonNull(status); triggerEvent = map(triggerEvent); actionPreview = map(actionPreview);
            Objects.requireNonNull(createdAt); error = clean(error);
        }
    }

    public record Incident(
            UUID id, String ownerId, String fingerprint, IncidentStatus status, String severity, String summary,
            String probableCause, List<Map<String, Object>> findings, List<Map<String, Object>> timeline,
            Instant openedAt, Instant updatedAt, Instant resolvedAt) {
        public Incident {
            Objects.requireNonNull(id); ownerId = owner(ownerId); fingerprint = text(fingerprint, "incident fingerprint");
            Objects.requireNonNull(status); severity = text(severity, "incident severity"); summary = text(summary, "incident summary");
            probableCause = clean(probableCause); findings = maps(findings); timeline = maps(timeline);
            Objects.requireNonNull(openedAt); Objects.requireNonNull(updatedAt);
        }
    }

    public record ExplainabilityTrace(
            UUID id, String ownerId, String conversationId, String responseId, String summary,
            List<String> sources, List<String> tools, Map<String, Object> decisions, Instant createdAt) {
        public ExplainabilityTrace {
            Objects.requireNonNull(id); ownerId = owner(ownerId); conversationId = text(conversationId, "conversation id");
            responseId = text(responseId, "response id"); summary = text(summary, "trace summary");
            sources = strings(sources); tools = strings(tools); decisions = map(decisions); Objects.requireNonNull(createdAt);
        }
    }

    public record TimelineEvent(
            UUID id, String ownerId, String eventType, String sourceType, String sourceId, String title,
            String summary, Map<String, Object> details, Instant occurredAt, Instant createdAt) {
        public TimelineEvent {
            Objects.requireNonNull(id); ownerId = owner(ownerId); eventType = text(eventType, "event type");
            sourceType = text(sourceType, "source type"); sourceId = text(sourceId, "source id");
            title = text(title, "timeline title"); summary = clean(summary); details = map(details);
            Objects.requireNonNull(occurredAt); Objects.requireNonNull(createdAt);
        }
    }

    public record PersonalExperiment(
            UUID id, String ownerId, String conversationId, UUID outcomeId, String title, String hypothesis,
            String protocol, String metricName, String metricUnit, MetricDirection direction,
            double baselineValue, double targetValue, int durationDays, ExperimentStatus status,
            Instant startedAt, Instant plannedEndAt, Instant createdAt, Instant updatedAt, Instant completedAt) {
        public PersonalExperiment {
            Objects.requireNonNull(id); ownerId = owner(ownerId);
            conversationId = text(conversationId, "conversation id"); Objects.requireNonNull(outcomeId);
            title = text(title, "experiment title"); hypothesis = text(hypothesis, "experiment hypothesis");
            protocol = text(protocol, "experiment protocol"); metricName = text(metricName, "metric name");
            metricUnit = text(metricUnit, "metric unit"); Objects.requireNonNull(direction);
            if (!Double.isFinite(baselineValue) || !Double.isFinite(targetValue)) {
                throw new IllegalArgumentException("experiment metric values must be finite");
            }
            if (direction == MetricDirection.INCREASE && targetValue <= baselineValue) {
                throw new IllegalArgumentException("increase target must be greater than baseline");
            }
            if (direction == MetricDirection.DECREASE && targetValue >= baselineValue) {
                throw new IllegalArgumentException("decrease target must be less than baseline");
            }
            if (durationDays < 1 || durationDays > 365) {
                throw new IllegalArgumentException("experiment duration must be between 1 and 365 days");
            }
            Objects.requireNonNull(status); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
            if ((status == ExperimentStatus.RUNNING || status == ExperimentStatus.PAUSED
                    || status == ExperimentStatus.COMPLETED) && (startedAt == null || plannedEndAt == null)) {
                throw new IllegalArgumentException("started experiment must have a start and planned end");
            }
        }
    }

    public record ExperimentCheckIn(
            UUID id, UUID experimentId, String ownerId, double value, String note,
            Instant observedAt, Instant createdAt) {
        public ExperimentCheckIn {
            Objects.requireNonNull(id); Objects.requireNonNull(experimentId); ownerId = owner(ownerId);
            if (!Double.isFinite(value)) throw new IllegalArgumentException("check-in value must be finite");
            note = clean(note); Objects.requireNonNull(observedAt); Objects.requireNonNull(createdAt);
        }
    }

    static String owner(String value) {
        String normalized = text(value, "owner id");
        if ("*".equals(normalized)) throw new IllegalArgumentException("owner id must not be wildcard");
        return normalized;
    }

    static String text(String value, String field) {
        String normalized = clean(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    static String clean(String value) { return Objects.requireNonNullElse(value, "").trim(); }
    static Map<String, Object> map(Map<String, Object> value) { return Map.copyOf(value == null ? Map.of() : value); }
    static List<String> strings(List<String> value) { return value == null ? List.of() : value.stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isBlank()).toList(); }
    static List<Map<String, Object>> maps(List<Map<String, Object>> value) { return value == null ? List.of() : value.stream().map(PersonalLoopModels::map).toList(); }
}
