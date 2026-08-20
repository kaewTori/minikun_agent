package com.minikun.knowledge;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.pcs.model.KnowledgeContext;

/** Local-first personal document ingestion and hybrid semantic/lexical retrieval. */
public final class PersonalKnowledgeService {
    private static final Logger LOG = LoggerFactory.getLogger(PersonalKnowledgeService.class);

    private final PersonalKnowledgeRepository repository;
    private final KnowledgeDocumentReader reader;
    private final KnowledgeChunker chunker;
    private final EmbeddingModel embeddingModel;
    private final String embeddingModelName;
    private final Clock clock;
    private final int maximumSearchCandidates;
    private final double semanticWeight;
    private final double minimumScore;
    private final AtomicBoolean semanticHealthy;

    public PersonalKnowledgeService(
            PersonalKnowledgeRepository repository,
            KnowledgeDocumentReader reader,
            KnowledgeChunker chunker,
            EmbeddingModel embeddingModel,
            String embeddingModelName,
            Clock clock,
            int maximumSearchCandidates,
            double semanticWeight,
            double minimumScore) {
        this.repository = java.util.Objects.requireNonNull(repository, "knowledge repository must not be null");
        this.reader = java.util.Objects.requireNonNull(reader, "knowledge reader must not be null");
        this.chunker = java.util.Objects.requireNonNull(chunker, "knowledge chunker must not be null");
        this.embeddingModel = embeddingModel;
        this.embeddingModelName = embeddingModelName == null ? "" : embeddingModelName.trim();
        this.clock = java.util.Objects.requireNonNull(clock, "knowledge clock must not be null");
        if (maximumSearchCandidates < 1 || semanticWeight < 0 || semanticWeight > 1
                || minimumScore < 0 || minimumScore > 1) {
            throw new IllegalArgumentException("invalid knowledge retrieval configuration");
        }
        this.maximumSearchCandidates = maximumSearchCandidates;
        this.semanticWeight = semanticWeight;
        this.minimumScore = minimumScore;
        this.semanticHealthy = new AtomicBoolean(embeddingModel != null);
    }

    public KnowledgeIndexReport index(String ownerId, String root, String path, boolean recursive, boolean force) {
        String owner = owner(ownerId);
        List<KnowledgeDocument> documents = reader.read(root, path, recursive);
        int indexed = 0;
        int unchanged = 0;
        int failed = 0;
        int chunks = 0;
        List<String> errors = new ArrayList<>();
        for (KnowledgeDocument document : documents) {
            try {
                IndexOutcome outcome = indexOne(owner, document, force);
                if (outcome.unchanged()) unchanged++;
                else {
                    indexed++;
                    chunks += outcome.chunks();
                }
            } catch (RuntimeException exception) {
                failed++;
                errors.add(document.relativePath() + ": " + safeError(exception.getMessage()));
                LOG.warn("process=personal_knowledge event=index_failed root={} path={}",
                        document.root(), document.relativePath(), exception);
            }
        }
        LOG.info("process=personal_knowledge event=index_completed owner_id={} discovered={} indexed={} "
                + "unchanged={} failed={} chunks={}", owner, documents.size(), indexed, unchanged, failed, chunks);
        return new KnowledgeIndexReport(root, path == null ? "" : path, documents.size(), indexed,
                unchanged, failed, chunks, errors);
    }

    public KnowledgeIndexReport reindex(String ownerId, boolean force) {
        String owner = owner(ownerId);
        List<KnowledgeSourceRecord> sources = repository.list(owner, 10_000);
        int indexed = 0;
        int unchanged = 0;
        int failed = 0;
        int chunks = 0;
        List<String> errors = new ArrayList<>();
        for (KnowledgeSourceRecord source : sources) {
            try {
                IndexOutcome outcome = indexOne(owner, reader.readRegistered(source), force);
                if (outcome.unchanged()) unchanged++;
                else {
                    indexed++;
                    chunks += outcome.chunks();
                }
            } catch (RuntimeException exception) {
                failed++;
                repository.fail(source.id(), clock.instant(), exception.getMessage());
                errors.add(source.path() + ": " + safeError(exception.getMessage()));
            }
        }
        return new KnowledgeIndexReport("*", "", sources.size(), indexed, unchanged, failed, chunks, errors);
    }

    public List<KnowledgeSearchResult> search(String ownerId, String query, int limit) {
        String owner = owner(ownerId);
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isBlank() || normalizedQuery.length() > 500) {
            throw new IllegalArgumentException("knowledge query must contain 1 to 500 characters");
        }
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("knowledge limit must be between 1 and 20");
        List<KnowledgeChunk> chunks = repository.chunks(owner, maximumSearchCandidates);
        if (chunks.isEmpty()) return List.of();
        float[] queryEmbedding = embed(normalizedQuery);
        Set<String> terms = terms(normalizedQuery);
        return chunks.stream().map(chunk -> score(chunk, normalizedQuery, terms, queryEmbedding))
                .filter(scored -> scored.score() >= minimumScore)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparing(scored -> scored.chunk().path())
                        .thenComparingInt(scored -> scored.chunk().index()))
                .limit(limit).map(this::result).toList();
    }

    public KnowledgeContext recall(String ownerId, String query, int limit) {
        List<KnowledgeSearchResult> results = search(ownerId, query, limit);
        List<KnowledgeCandidate> candidates = new ArrayList<>(results.size());
        for (KnowledgeSearchResult result : results) {
            String content = "[Personal Knowledge | " + result.source() + " | " + result.citation() + "]\n"
                    + result.content();
            candidates.add(new KnowledgeCandidate(
                    "personal-" + result.sourceId() + "-" + result.chunk(),
                    KnowledgeSource.PERSONAL, content, result.chunk(), result.citation()));
        }
        return KnowledgeContext.fromCandidates(candidates);
    }

    public List<KnowledgeSourceRecord> sources(String ownerId, int limit) {
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("source limit must be between 1 and 10000");
        return repository.list(owner(ownerId), limit);
    }

    public boolean delete(String ownerId, UUID sourceId) {
        if (sourceId == null) throw new IllegalArgumentException("knowledge source id is required");
        return repository.delete(owner(ownerId), sourceId);
    }

    public KnowledgeStatus status(String ownerId) {
        String owner = owner(ownerId);
        return new KnowledgeStatus(true, semanticHealthy.get(), embeddingModelName,
                repository.sourceCount(owner), repository.chunkCount(owner), reader.rootStatus(), true);
    }

    private IndexOutcome indexOne(String owner, KnowledgeDocument document, boolean force) {
        var existing = repository.find(owner, document.root(), document.relativePath());
        if (!force && existing.filter(source -> document.hash().equals(source.fileHash())
                && "READY".equals(source.status())).isPresent()) return new IndexOutcome(true, 0);
        UUID sourceId = repository.begin(owner, document.root(), document.relativePath(), document.name());
        try {
            List<KnowledgeChunker.ChunkText> texts = chunker.chunk(document.content());
            if (texts.isEmpty()) throw new IllegalArgumentException("knowledge document produced no chunks");
            List<KnowledgeChunkDraft> drafts = new ArrayList<>(texts.size());
            boolean semantic = embeddingModel != null;
            for (int index = 0; index < texts.size(); index++) {
                KnowledgeChunker.ChunkText text = texts.get(index);
                String embedding = "";
                String model = "";
                if (semantic) {
                    try {
                        embedding = KnowledgeEmbeddingCodec.encode(embeddingModel.embed(text.content()));
                        model = embeddingModelName;
                        semanticHealthy.set(true);
                    } catch (RuntimeException exception) {
                        semantic = false;
                        semanticHealthy.set(false);
                        LOG.warn("process=personal_knowledge event=embedding_fallback path={}",
                                document.relativePath());
                    }
                }
                drafts.add(new KnowledgeChunkDraft(index, text.heading(), text.content(), text.hash(),
                        embedding, model));
            }
            repository.replace(sourceId, document.hash(), document.modifiedAt(), clock.instant(), drafts);
            return new IndexOutcome(false, drafts.size());
        } catch (RuntimeException exception) {
            repository.fail(sourceId, clock.instant(), exception.getMessage());
            throw exception;
        }
    }

    private ScoredChunk score(KnowledgeChunk chunk, String query, Set<String> terms, float[] queryEmbedding) {
        double lexical = lexical(chunk, query, terms);
        boolean semantic = queryEmbedding.length > 0 && embeddingModelName.equals(chunk.embeddingModel());
        double semanticScore = 0;
        if (semantic) {
            try {
                float[] vector = KnowledgeEmbeddingCodec.decode(chunk.embedding());
                semanticScore = (cosine(queryEmbedding, vector) + 1.0) / 2.0;
            } catch (RuntimeException exception) {
                semantic = false;
            }
        }
        double blended = semanticScore * semanticWeight + lexical * (1.0 - semanticWeight);
        // Strong literal evidence must not be suppressed by a multilingual embedding that is
        // less confident for Thai. Semantic similarity can raise, but never lower, lexical evidence.
        double score = semantic ? Math.max(lexical, blended) : lexical;
        return new ScoredChunk(chunk, score, semantic);
    }

    private double lexical(KnowledgeChunk chunk, String query, Set<String> terms) {
        String text = (chunk.sourceName() + " " + chunk.path() + " " + chunk.heading() + " " + chunk.content())
                .toLowerCase(Locale.ROOT);
        String normalized = query.toLowerCase(Locale.ROOT);
        double phrase = text.contains(normalized) ? 1.0 : 0.0;
        if (terms.isEmpty()) return phrase;
        long matches = terms.stream().filter(text::contains).count();
        return Math.min(1.0, phrase * 0.45 + ((double) matches / terms.size()) * 0.55);
    }

    private Set<String> terms(String query) {
        Set<String> result = new HashSet<>();
        Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(value -> value.length() > 1).forEach(result::add);
        String compact = query.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (result.size() <= 1 && compact.length() >= 3) {
            for (int index = 0; index <= compact.length() - 3; index++) result.add(compact.substring(index, index + 3));
        }
        return Set.copyOf(result);
    }

    private KnowledgeSearchResult result(ScoredChunk scored) {
        KnowledgeChunk chunk = scored.chunk();
        String citation = "knowledge://" + chunk.root() + "/" + chunk.path() + "#chunk=" + chunk.index();
        return new KnowledgeSearchResult(chunk.sourceId(), chunk.sourceName(), chunk.root(), chunk.path(),
                chunk.index(), chunk.heading(), chunk.content(), citation,
                Math.round(scored.score() * 10_000.0) / 10_000.0, scored.semantic());
    }

    private float[] embed(String value) {
        if (embeddingModel == null) return new float[0];
        try {
            String instructedQuery = "Instruct: Retrieve passages from the user's personal documents that "
                    + "directly answer the question.\nQuery: " + value;
            float[] result = embeddingModel.embed(instructedQuery);
            semanticHealthy.set(true);
            return result;
        } catch (RuntimeException exception) {
            semanticHealthy.set(false);
            LOG.warn("process=personal_knowledge event=query_embedding_fallback");
            return new float[0];
        }
    }

    private double cosine(float[] left, float[] right) {
        if (left.length == 0 || left.length != right.length) throw new IllegalArgumentException("embedding mismatch");
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) throw new IllegalArgumentException("zero embedding");
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private String owner(String value) {
        String owner = value == null || value.isBlank() ? "default" : value.trim();
        if (owner.length() > 255 || "*".equals(owner) || owner.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid knowledge owner id");
        }
        return owner;
    }

    private String safeError(String value) {
        String result = value == null || value.isBlank() ? "indexing failed" : value.replaceAll("[\\r\\n]+", " ");
        return result.length() <= 200 ? result : result.substring(0, 200);
    }

    private record IndexOutcome(boolean unchanged, int chunks) {}
    private record ScoredChunk(KnowledgeChunk chunk, double score, boolean semantic) {}
}
