package com.minikun.knowledge.acquisition;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable public model for autonomous knowledge acquisition. */
public final class KnowledgeAcquisitionModels {
    private KnowledgeAcquisitionModels() { }

    public enum TopicOrigin { SUBSCRIBED, INTEREST_INFERRED, KNOWLEDGE_GAP, SYSTEM_DEPENDENCY }
    public enum TopicStatus { ACTIVE, PAUSED }
    public enum SourcePolicy { OFFICIAL_ONLY, OFFICIAL_FIRST, BALANCED }
    public enum RunTrigger { MANUAL, SCHEDULED }
    public enum RunStatus { RUNNING, COMPLETED, FAILED }
    public enum SourceKind { SEARCH_SNIPPET, BROWSER_DOCUMENT }
    public enum ClaimStatus { CANDIDATE, VERIFIED, PUBLISHED, DISPUTED, RETRACTED, STALE }
    public enum ExtractionMethod { MODEL, FALLBACK }

    public enum RefreshPolicy {
        HOURLY(Duration.ofHours(1)),
        DAILY(Duration.ofDays(1)),
        WEEKLY(Duration.ofDays(7)),
        MONTHLY(Duration.ofDays(30)),
        MANUAL(null);

        private final Duration interval;

        RefreshPolicy(Duration interval) { this.interval = interval; }

        public Instant nextAfter(Instant value) {
            return interval == null ? null : value.plus(interval);
        }

        public Instant expiresAfter(Instant value) {
            return interval == null ? value.plus(90, ChronoUnit.DAYS)
                    : value.plus(interval.multipliedBy(2));
        }
    }

    public record Topic(
            UUID id,
            String ownerId,
            String name,
            String objective,
            TopicOrigin origin,
            int priority,
            RefreshPolicy refreshPolicy,
            SourcePolicy sourcePolicy,
            List<String> trustedDomains,
            TopicStatus status,
            Instant nextRunAt,
            Instant lastRunAt,
            Instant createdAt,
            Instant updatedAt) {
        public Topic {
            id = Objects.requireNonNull(id, "topic id must not be null");
            ownerId = required(ownerId, "owner id", 255);
            name = required(name, "topic name", 200);
            objective = required(objective, "topic objective", 2_000);
            origin = Objects.requireNonNull(origin, "topic origin must not be null");
            refreshPolicy = Objects.requireNonNull(refreshPolicy, "refresh policy must not be null");
            sourcePolicy = Objects.requireNonNull(sourcePolicy, "source policy must not be null");
            status = Objects.requireNonNull(status, "topic status must not be null");
            createdAt = Objects.requireNonNull(createdAt, "created time must not be null");
            updatedAt = Objects.requireNonNull(updatedAt, "updated time must not be null");
            if (priority < 0 || priority > 100) {
                throw new IllegalArgumentException("topic priority must be between 0 and 100");
            }
            trustedDomains = trustedDomains == null ? List.of() : trustedDomains.stream()
                    .filter(Objects::nonNull).map(String::strip).filter(value -> !value.isBlank())
                    .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                    .distinct().limit(50).toList();
            if (sourcePolicy == SourcePolicy.OFFICIAL_ONLY && trustedDomains.isEmpty()) {
                throw new IllegalArgumentException("official-only topics require at least one trusted domain");
            }
        }

        public Topic scheduleAfter(Instant runAt) {
            return new Topic(id, ownerId, name, objective, origin, priority, refreshPolicy, sourcePolicy,
                    trustedDomains, status, refreshPolicy.nextAfter(runAt), runAt, createdAt, runAt);
        }
    }

    public record AcquisitionRun(
            UUID id,
            UUID topicId,
            String ownerId,
            RunStatus status,
            RunTrigger trigger,
            String objective,
            String trace,
            String stopReason,
            int sourceCount,
            int candidateCount,
            int publishedCount,
            String error,
            Instant startedAt,
            Instant completedAt) {
        public AcquisitionRun {
            id = Objects.requireNonNull(id);
            topicId = Objects.requireNonNull(topicId);
            ownerId = required(ownerId, "owner id", 255);
            status = Objects.requireNonNull(status);
            trigger = Objects.requireNonNull(trigger);
            objective = required(objective, "run objective", 2_000);
            trace = bounded(trace, 20_000);
            stopReason = bounded(stopReason, 100);
            error = bounded(error, 2_000);
            startedAt = Objects.requireNonNull(startedAt);
            if (sourceCount < 0 || candidateCount < 0 || publishedCount < 0) {
                throw new IllegalArgumentException("run counts must not be negative");
            }
        }
    }

    public record ExternalSource(
            UUID id,
            UUID runId,
            UUID topicId,
            String ownerId,
            String url,
            SourceKind kind,
            String contentExcerpt,
            String contentHash,
            Instant fetchedAt) {
        public ExternalSource {
            id = Objects.requireNonNull(id);
            runId = Objects.requireNonNull(runId);
            topicId = Objects.requireNonNull(topicId);
            ownerId = required(ownerId, "owner id", 255);
            url = required(url, "source url", 2_000);
            kind = Objects.requireNonNull(kind);
            contentExcerpt = bounded(contentExcerpt, 12_000);
            contentHash = required(contentHash, "content hash", 128);
            fetchedAt = Objects.requireNonNull(fetchedAt);
        }
    }

    public record Claim(
            UUID id,
            UUID topicId,
            String ownerId,
            String topicName,
            String text,
            String fingerprint,
            ClaimStatus status,
            double confidence,
            List<String> evidenceUrls,
            String verificationReason,
            @JsonIgnore String embedding,
            String embeddingModel,
            Instant discoveredAt,
            Instant verifiedAt,
            Instant publishedAt,
            Instant expiresAt,
            Instant updatedAt) {
        public Claim {
            id = Objects.requireNonNull(id);
            topicId = Objects.requireNonNull(topicId);
            ownerId = required(ownerId, "owner id", 255);
            topicName = required(topicName, "topic name", 200);
            text = required(text, "claim text", 2_000);
            fingerprint = required(fingerprint, "claim fingerprint", 128);
            status = Objects.requireNonNull(status);
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("claim confidence must be between 0 and 1");
            }
            evidenceUrls = evidenceUrls == null ? List.of() : evidenceUrls.stream()
                    .filter(Objects::nonNull).map(String::strip).filter(value -> !value.isBlank())
                    .distinct().limit(20).toList();
            verificationReason = bounded(verificationReason, 500);
            embedding = Objects.requireNonNullElse(embedding, "");
            embeddingModel = Objects.requireNonNullElse(embeddingModel, "");
            discoveredAt = Objects.requireNonNull(discoveredAt);
            updatedAt = Objects.requireNonNull(updatedAt);
        }

        public Claim withStatus(ClaimStatus next, Instant now) {
            Instant verified = next == ClaimStatus.VERIFIED || next == ClaimStatus.PUBLISHED
                    ? Objects.requireNonNullElse(verifiedAt, now) : verifiedAt;
            Instant published = next == ClaimStatus.PUBLISHED
                    ? Objects.requireNonNullElse(publishedAt, now) : publishedAt;
            return new Claim(id, topicId, ownerId, topicName, text, fingerprint, next, confidence,
                    evidenceUrls, verificationReason, embedding, embeddingModel, discoveredAt,
                    verified, published, expiresAt, now);
        }
    }

    record ClaimDraft(String text, List<Integer> evidenceIndexes, double confidence,
            ExtractionMethod extractionMethod) {
        ClaimDraft {
            text = required(text, "claim draft text", 2_000);
            evidenceIndexes = evidenceIndexes == null ? List.of() : evidenceIndexes.stream()
                    .filter(Objects::nonNull).filter(value -> value >= 0).distinct().limit(20).toList();
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
                throw new IllegalArgumentException("draft confidence must be between 0 and 1");
            }
            extractionMethod = Objects.requireNonNull(extractionMethod);
        }
    }

    record Verification(ClaimStatus status, double confidence, String reason) {
        Verification {
            status = Objects.requireNonNull(status);
            reason = bounded(reason, 500);
        }
    }

    private static String required(String value, String field, int maximum) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > maximum) throw new IllegalArgumentException(field + " is too long");
        return normalized;
    }

    private static String bounded(String value, int maximum) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
}
