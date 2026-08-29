package com.minikun.knowledge.acquisition;

import static com.minikun.knowledge.acquisition.KnowledgeAcquisitionModels.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owner-scoped management facade and single-process run claim boundary. */
public final class KnowledgeAcquisitionService {
    private final KnowledgeAcquisitionStore store;
    private final KnowledgeAcquisitionAgent agent;
    private final Clock clock;
    private final Set<UUID> activeRuns = ConcurrentHashMap.newKeySet();

    KnowledgeAcquisitionService(KnowledgeAcquisitionStore store, KnowledgeAcquisitionAgent agent, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.agent = Objects.requireNonNull(agent);
        this.clock = Objects.requireNonNull(clock);
    }

    public Topic createTopic(String ownerId, String name, String objective, TopicOrigin origin, Integer priority,
            RefreshPolicy refreshPolicy, SourcePolicy sourcePolicy, List<String> trustedDomains,
            TopicStatus status) {
        Instant now = clock.instant();
        RefreshPolicy refresh = Objects.requireNonNullElse(refreshPolicy, RefreshPolicy.WEEKLY);
        TopicStatus topicStatus = Objects.requireNonNullElse(status, TopicStatus.ACTIVE);
        Instant next = topicStatus == TopicStatus.ACTIVE && refresh != RefreshPolicy.MANUAL ? now : null;
        return store.saveTopic(new Topic(UUID.randomUUID(), owner(ownerId), name, objective,
                Objects.requireNonNullElse(origin, TopicOrigin.SUBSCRIBED), priority == null ? 50 : priority,
                refresh, Objects.requireNonNullElse(sourcePolicy, SourcePolicy.OFFICIAL_FIRST), trustedDomains,
                topicStatus, next, null, now, now));
    }

    public Topic updateTopic(String ownerId, UUID id, String name, String objective, Integer priority,
            RefreshPolicy refreshPolicy, SourcePolicy sourcePolicy, List<String> trustedDomains,
            TopicStatus status) {
        Topic current = topic(ownerId, id);
        Instant now = clock.instant();
        RefreshPolicy refresh = refreshPolicy == null ? current.refreshPolicy() : refreshPolicy;
        TopicStatus nextStatus = status == null ? current.status() : status;
        Instant nextRun = nextStatus == TopicStatus.PAUSED || refresh == RefreshPolicy.MANUAL ? null
                : current.nextRunAt() == null ? now : current.nextRunAt();
        return store.saveTopic(new Topic(current.id(), current.ownerId(), value(name, current.name()),
                value(objective, current.objective()), current.origin(), priority == null ? current.priority() : priority,
                refresh, sourcePolicy == null ? current.sourcePolicy() : sourcePolicy,
                trustedDomains == null ? current.trustedDomains() : trustedDomains, nextStatus, nextRun,
                current.lastRunAt(), current.createdAt(), now));
    }

    public List<Topic> topics(String ownerId, TopicStatus status, int limit) {
        return store.topics(owner(ownerId), status, bounded(limit, 200));
    }

    public boolean deleteTopic(String ownerId, UUID id) {
        return store.deleteTopic(owner(ownerId), Objects.requireNonNull(id));
    }

    public AcquisitionRun runNow(String ownerId, UUID topicId) {
        return execute(topic(ownerId, topicId), RunTrigger.MANUAL);
    }

    public List<AcquisitionRun> runDue(String ownerId, int limit) {
        Instant now = clock.instant();
        return store.due(owner(ownerId), now, bounded(limit, 10)).stream()
                .map(topic -> execute(topic, RunTrigger.SCHEDULED)).toList();
    }

    public List<AcquisitionRun> runs(String ownerId, UUID topicId, int limit) {
        return store.runs(owner(ownerId), topicId, bounded(limit, 200));
    }

    public List<Claim> claims(String ownerId, ClaimStatus status, int limit) {
        return store.claims(owner(ownerId), status, bounded(limit, 500));
    }

    public Claim reviewClaim(String ownerId, UUID id, ClaimStatus status) {
        if (status != ClaimStatus.PUBLISHED && status != ClaimStatus.CANDIDATE
                && status != ClaimStatus.DISPUTED && status != ClaimStatus.RETRACTED) {
            throw new IllegalArgumentException("review status must be PUBLISHED, CANDIDATE, DISPUTED or RETRACTED");
        }
        Claim current = store.claim(owner(ownerId), Objects.requireNonNull(id))
                .orElseThrow(() -> new IllegalArgumentException("knowledge claim was not found"));
        Instant now = clock.instant();
        Claim reviewed = current.withStatus(status, now);
        if (status == ClaimStatus.PUBLISHED
                && (reviewed.expiresAt() == null || !reviewed.expiresAt().isAfter(now))) {
            Topic topic = topic(ownerId, reviewed.topicId());
            reviewed = new Claim(reviewed.id(), reviewed.topicId(), reviewed.ownerId(), reviewed.topicName(),
                    reviewed.text(), reviewed.fingerprint(), reviewed.status(), reviewed.confidence(),
                    reviewed.evidenceUrls(), reviewed.verificationReason(), reviewed.embedding(),
                    reviewed.embeddingModel(), reviewed.discoveredAt(), reviewed.verifiedAt(),
                    reviewed.publishedAt(), topic.refreshPolicy().expiresAfter(now), now);
        }
        return store.saveClaim(reviewed);
    }

    public int expireStaleClaims() { return store.markExpired(clock.instant()); }

    private AcquisitionRun execute(Topic topic, RunTrigger trigger) {
        if (!activeRuns.add(topic.id())) throw new IllegalStateException("knowledge topic is already running");
        try { return agent.run(topic, trigger); }
        finally { activeRuns.remove(topic.id()); }
    }

    private Topic topic(String ownerId, UUID id) {
        return store.topic(owner(ownerId), Objects.requireNonNull(id))
                .orElseThrow(() -> new IllegalArgumentException("knowledge topic was not found"));
    }

    private int bounded(int value, int maximum) {
        if (value < 1 || value > maximum) throw new IllegalArgumentException("limit must be between 1 and " + maximum);
        return value;
    }

    private String owner(String value) {
        String owner = Objects.requireNonNullElse(value, "").strip();
        if (owner.isBlank() || "*".equals(owner) || owner.length() > 255) {
            throw new IllegalArgumentException("owner id is invalid");
        }
        return owner;
    }

    private String value(String proposed, String fallback) {
        return proposed == null || proposed.isBlank() ? fallback : proposed;
    }
}
