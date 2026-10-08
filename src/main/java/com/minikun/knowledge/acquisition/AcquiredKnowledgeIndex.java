package com.minikun.knowledge.acquisition;

import static com.minikun.knowledge.acquisition.KnowledgeAcquisitionModels.*;

import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.knowledge.EmbeddingSupport;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.scheduling.annotation.Scheduled;
import lombok.extern.slf4j.Slf4j;

/** Hybrid retrieval index for verified externally acquired claims. */
@Slf4j
public final class AcquiredKnowledgeIndex {
    private final KnowledgeAcquisitionStore store;
    private final EmbeddingModel embeddingModel;
    private final String embeddingModelName;
    private final Clock clock;
    private final int maximumCandidates;
    private final double semanticWeight;
    private final double minimumScore;

    AcquiredKnowledgeIndex(KnowledgeAcquisitionStore store, EmbeddingModel embeddingModel,
            String embeddingModelName, Clock clock, int maximumCandidates,
            double semanticWeight, double minimumScore) {
        this.store = Objects.requireNonNull(store);
        this.embeddingModel = embeddingModel;
        this.embeddingModelName = Objects.requireNonNullElse(embeddingModelName, "").strip();
        this.clock = Objects.requireNonNull(clock);
        if (maximumCandidates < 1 || semanticWeight < 0 || semanticWeight > 1
                || minimumScore < 0 || minimumScore > 1) {
            throw new IllegalArgumentException("invalid acquired knowledge retrieval configuration");
        }
        this.maximumCandidates = maximumCandidates;
        this.semanticWeight = semanticWeight;
        this.minimumScore = minimumScore;
    }

    EmbeddingValue embed(String title, String text) {
        if (embeddingModel == null) return new EmbeddingValue("", "");
        try {
            return new EmbeddingValue(encode(embeddingModel.embed(
                    EmbeddingSupport.document(embeddingModelName, title, text))), embeddingModelName);
        } catch (RuntimeException exception) {
            return new EmbeddingValue("", "");
        }
    }

    @Scheduled(initialDelayString = "${minikun.knowledge-acquisition.embedding.backfill-initial-delay:10000}",
            fixedDelayString = "${minikun.knowledge-acquisition.embedding.backfill-delay:30000}")
    void backfillEmbeddings() {
        if (embeddingModel == null) return;
        try {
            List<Claim> rows = store.embeddingsToRefresh(embeddingModelName, 64);
            if (rows.isEmpty()) return;
            List<float[]> vectors = embeddingModel.embed(rows.stream().map(claim ->
                    EmbeddingSupport.document(embeddingModelName, claim.topicName(), claim.text())).toList());
            if (vectors == null || vectors.size() != rows.size())
                throw new IllegalStateException("embedding batch size mismatch");
            List<String> encoded = vectors.stream().map(this::encode).toList();
            for (int i = 0; i < rows.size(); i++)
                store.updateEmbedding(rows.get(i), encoded.get(i), embeddingModelName);
            log.info("acquired_knowledge_embedding_backfill rows={}", rows.size());
        } catch (RuntimeException exception) {
            log.warn("acquired_knowledge_embedding_backfill_failed error_type={}", exception.getClass().getSimpleName());
        }
    }

    public KnowledgeContext recall(String ownerId, String query, int limit) {
        String owner = required(ownerId, "owner id");
        String normalized = required(query, "knowledge query");
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("knowledge limit must be between 1 and 20");
        List<Claim> claims = store.published(owner, clock.instant(), maximumCandidates);
        if (claims.isEmpty()) return KnowledgeContext.empty();
        float[] queryEmbedding = queryEmbedding(normalized);
        Set<String> terms = terms(normalized);
        List<Scored> scored = claims.stream().map(value -> score(value, normalized, terms, queryEmbedding))
                .filter(value -> value.score() >= minimumScore)
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(value -> value.claim().topicName()))
                .limit(limit).toList();
        List<KnowledgeCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < scored.size(); index++) {
            Claim claim = scored.get(index).claim();
            String provenance = claim.evidenceUrls().isEmpty() ? "" : claim.evidenceUrls().getFirst();
            String content = "[Verified acquired knowledge | " + claim.topicName()
                    + " | confidence=" + Math.round(claim.confidence() * 100) / 100d + "]\n" + claim.text();
            candidates.add(new KnowledgeCandidate("acquired-" + claim.id(), KnowledgeSource.PERSONAL,
                    content, index, provenance));
        }
        return KnowledgeContext.fromCandidates(candidates);
    }

    private Scored score(Claim claim, String query, Set<String> terms, float[] queryEmbedding) {
        String text = (claim.topicName() + " " + claim.text()).toLowerCase(Locale.ROOT);
        String normalized = query.toLowerCase(Locale.ROOT);
        double phrase = text.contains(normalized) ? 1 : 0;
        long matches = terms.stream().filter(text::contains).count();
        double lexical = terms.isEmpty() ? phrase
                : Math.min(1, phrase * .45 + matches / (double) terms.size() * .55);
        double semantic = 0;
        boolean semanticAvailable = queryEmbedding.length > 0
                && embeddingModelName.equals(claim.embeddingModel()) && !claim.embedding().isBlank();
        if (semanticAvailable) {
            try { semantic = (cosine(queryEmbedding, decode(claim.embedding())) + 1) / 2; }
            catch (RuntimeException exception) { semanticAvailable = false; }
        }
        double relevance = semanticAvailable ? Math.max(lexical,
                semantic * semanticWeight + lexical * (1 - semanticWeight)) : lexical;
        return new Scored(claim, relevance * (.75 + claim.confidence() * .25));
    }

    private float[] queryEmbedding(String query) {
        if (embeddingModel == null) return new float[0];
        try {
            return EmbeddingSupport.requireVector(embeddingModel.embed(EmbeddingSupport.query(
                    embeddingModelName, query, "Retrieve verified external knowledge that directly answers the question.")));
        } catch (RuntimeException exception) {
            return new float[0];
        }
    }

    private Set<String> terms(String query) {
        Set<String> result = new HashSet<>();
        Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(value -> value.length() > 1).forEach(result::add);
        String compact = query.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (result.size() <= 1 && compact.length() >= 3) {
            for (int index = 0; index <= compact.length() - 3; index++) {
                result.add(compact.substring(index, index + 3));
            }
        }
        return Set.copyOf(result);
    }

    private double cosine(float[] left, float[] right) {
        EmbeddingSupport.requireVector(left);
        EmbeddingSupport.requireVector(right);
        if (left.length != right.length) throw new IllegalArgumentException("embedding mismatch");
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        return leftNorm == 0 || rightNorm == 0 ? -1 : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private String encode(float[] vector) {
        EmbeddingSupport.requireVector(vector);
        StringBuilder value = new StringBuilder(vector.length * 10);
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) value.append(',');
            value.append(vector[index]);
        }
        return value.toString();
    }

    private float[] decode(String value) {
        if (value == null || value.isBlank()) return new float[0];
        String[] fields = value.split(",");
        float[] vector = new float[fields.length];
        for (int index = 0; index < fields.length; index++) vector[index] = Float.parseFloat(fields[index]);
        return vector;
    }

    private String required(String value, String field) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    record EmbeddingValue(String value, String model) { }
    private record Scored(Claim claim, double score) { }
}
