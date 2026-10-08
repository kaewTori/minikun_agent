package com.minikun.knowledge.acquisition;

import static com.minikun.knowledge.acquisition.KnowledgeAcquisitionModels.*;

import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.research.AutonomousResearchRequest;
import com.minikun.research.AutonomousResearchResult;
import com.minikun.research.AutonomousResearchService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/** Durable bounded agent that researches one topic and publishes only claims that pass verification. */
@Slf4j
final class KnowledgeAcquisitionAgent {
    private final KnowledgeAcquisitionStore store;
    private final AutonomousResearchService research;
    private final KnowledgeClaimExtractor extractor;
    private final KnowledgeClaimVerifier verifier;
    private final AcquiredKnowledgeIndex index;
    private final Clock clock;
    private final Duration timeout;
    private final int resultLimit;
    private final int sourceReadLimit;
    private final boolean safeSearch;

    KnowledgeAcquisitionAgent(KnowledgeAcquisitionStore store, AutonomousResearchService research,
            KnowledgeClaimExtractor extractor, KnowledgeClaimVerifier verifier, AcquiredKnowledgeIndex index,
            Clock clock, Duration timeout, int resultLimit, int sourceReadLimit, boolean safeSearch) {
        this.store = Objects.requireNonNull(store);
        this.research = Objects.requireNonNull(research);
        this.extractor = Objects.requireNonNull(extractor);
        this.verifier = Objects.requireNonNull(verifier);
        this.index = Objects.requireNonNull(index);
        this.clock = Objects.requireNonNull(clock);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("acquisition timeout must be positive");
        if (resultLimit < 1 || resultLimit > 100 || sourceReadLimit < 1 || sourceReadLimit > 20) {
            throw new IllegalArgumentException("invalid acquisition source limits");
        }
        this.resultLimit = resultLimit;
        this.sourceReadLimit = sourceReadLimit;
        this.safeSearch = safeSearch;
    }

    AcquisitionRun run(Topic topic, RunTrigger trigger) {
        Instant started = clock.instant();
        AcquisitionRun active = store.saveRun(new AcquisitionRun(UUID.randomUUID(), topic.id(), topic.ownerId(),
                RunStatus.RUNNING, trigger, topic.objective(), "", "", 0, 0, 0, "", started, null));
        try {
            AutonomousResearchResult result = research.research(new AutonomousResearchRequest(
                    topic.objective(), "Persistent knowledge topic: " + topic.name(), primaryQuery(topic),
                    seedQueries(topic), "all", "", safeSearch, resultLimit, sourceReadLimit,
                    topic.trustedDomains(),
                    started.plus(timeout)));
            List<KnowledgeCandidate> evidence = evidence(result);
            saveSources(active, topic, evidence, started);
            List<ClaimDraft> drafts = extractor.extract(topic, evidence);
            int published = 0;
            for (ClaimDraft draft : drafts) {
                Verification verification = verifier.verify(topic, draft, evidence);
                List<String> urls = evidenceUrls(draft, evidence);
                AcquiredKnowledgeIndex.EmbeddingValue embedding = index.embed(topic.name(), draft.text());
                Instant now = clock.instant();
                Claim claim = new Claim(UUID.randomUUID(), topic.id(), topic.ownerId(), topic.name(), draft.text(),
                        fingerprint(draft.text()), verification.status(), verification.confidence(), urls,
                        verification.reason(), embedding.value(), embedding.model(), now,
                        verification.status() == ClaimStatus.PUBLISHED ? now : null,
                        verification.status() == ClaimStatus.PUBLISHED ? now : null,
                        topic.refreshPolicy().expiresAfter(now), now);
                Claim saved = store.saveClaim(claim);
                if (verification.status() == ClaimStatus.PUBLISHED && saved.status() == ClaimStatus.PUBLISHED) published++;
            }
            Instant completed = clock.instant();
            store.saveTopic(topic.scheduleAfter(completed));
            AcquisitionRun done = new AcquisitionRun(active.id(), active.topicId(), active.ownerId(),
                    RunStatus.COMPLETED, active.trigger(), active.objective(), result.trace().promptSummary(),
                    result.trace().stopReason().name(), evidence.size(), drafts.size(), published, "",
                    active.startedAt(), completed);
            log.info("process=knowledge_acquisition event=completed topic_id={} sources={} claims={} published={}",
                    topic.id(), evidence.size(), drafts.size(), published);
            return store.saveRun(done);
        } catch (RuntimeException exception) {
            Instant completed = clock.instant();
            store.saveTopic(topic.scheduleAfter(completed));
            AcquisitionRun failed = new AcquisitionRun(active.id(), active.topicId(), active.ownerId(),
                    RunStatus.FAILED, active.trigger(), active.objective(), "", "FAILED", 0, 0, 0,
                    safeError(exception), active.startedAt(), completed);
            log.warn("process=knowledge_acquisition event=failed topic_id={} reason={}",
                    topic.id(), exception.getMessage());
            return store.saveRun(failed);
        }
    }

    private List<KnowledgeCandidate> evidence(AutonomousResearchResult result) {
        LinkedHashMap<String, KnowledgeCandidate> values = new LinkedHashMap<>();
        result.browserCandidates().forEach(candidate -> values.put(key(candidate), candidate));
        result.searchKnowledge().candidates().forEach(candidate -> values.putIfAbsent(key(candidate), candidate));
        return List.copyOf(values.values());
    }

    private String primaryQuery(Topic topic) {
        if (topic.sourcePolicy() != SourcePolicy.BALANCED && !topic.trustedDomains().isEmpty()) {
            return topic.name() + " site:" + topic.trustedDomains().getFirst();
        }
        return topic.name();
    }

    private List<String> seedQueries(Topic topic) {
        List<String> queries = new ArrayList<>();
        if (topic.sourcePolicy() != SourcePolicy.BALANCED) {
            topic.trustedDomains().stream().limit(4)
                    .map(domain -> topic.name() + " site:" + domain)
                    .forEach(queries::add);
        }
        if (topic.sourcePolicy() != SourcePolicy.OFFICIAL_ONLY) queries.add(topic.objective());
        return queries.stream().distinct().toList();
    }

    private void saveSources(AcquisitionRun run, Topic topic, List<KnowledgeCandidate> evidence, Instant fetchedAt) {
        for (KnowledgeCandidate candidate : evidence) {
            String url = candidate.provenance().isBlank() ? "urn:minikun:source:" + fingerprint(candidate.content())
                    : candidate.provenance();
            store.saveSource(new ExternalSource(UUID.randomUUID(), run.id(), topic.id(), topic.ownerId(), url,
                    candidate.source() == KnowledgeSource.BROWSER
                            ? SourceKind.BROWSER_DOCUMENT : SourceKind.SEARCH_SNIPPET,
                    candidate.content(), fingerprint(candidate.content()), fetchedAt));
        }
    }

    private List<String> evidenceUrls(ClaimDraft draft, List<KnowledgeCandidate> evidence) {
        List<String> values = new ArrayList<>();
        for (int candidateIndex : draft.evidenceIndexes()) {
            if (candidateIndex >= 0 && candidateIndex < evidence.size()) {
                String url = evidence.get(candidateIndex).provenance();
                if (url != null && !url.isBlank() && !values.contains(url)) values.add(url);
            }
        }
        return List.copyOf(values);
    }

    private String key(KnowledgeCandidate candidate) {
        return candidate.provenance().isBlank() ? fingerprint(candidate.content()) : candidate.provenance();
    }

    static String fingerprint(String value) {
        try {
            String normalized = Objects.requireNonNullElse(value, "").toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ").strip();
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String safeError(RuntimeException exception) {
        String value = Objects.requireNonNullElse(exception.getMessage(), exception.getClass().getSimpleName());
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }
}
