package com.minikun.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.minikun.pcs.KnowledgeSource;
import org.springframework.ai.embedding.EmbeddingModel;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

class PersonalKnowledgeServiceTest {
    @TempDir Path root;

    @Test
    void indexesIncrementallyAndReturnsCitedPersonalKnowledge() throws Exception {
        Files.writeString(root.resolve("architecture.md"),
                "# Minikun Architecture\nThe project stores personal knowledge in PostgreSQL safely.");
        InMemoryRepository repository = new InMemoryRepository();
        PersonalKnowledgeService service = service(repository);

        KnowledgeIndexReport first = service.index("default", "knowledge", "architecture.md", false, false);
        KnowledgeIndexReport second = service.index("default", "knowledge", "architecture.md", false, false);
        List<KnowledgeSearchResult> results = service.search("default", "PostgreSQL", 5);

        assertEquals(1, first.indexed());
        assertEquals(1, first.chunks());
        assertEquals(1, second.unchanged());
        assertEquals(1, results.size());
        assertTrue(results.getFirst().citation().startsWith("knowledge://knowledge/architecture.md#chunk="));
        assertFalse(results.getFirst().semantic());
        var recalled = service.recall("default", "PostgreSQL", 5);
        assertEquals(KnowledgeSource.PERSONAL, recalled.candidates().getFirst().source());
        assertTrue(recalled.content().contains("knowledge://knowledge/architecture.md#chunk="));
    }

    @Test
    void ownerScopePreventsCrossOwnerRetrievalAndDelete() throws Exception {
        Files.writeString(root.resolve("private.md"), "Owner alpha secret project orchid");
        InMemoryRepository repository = new InMemoryRepository();
        PersonalKnowledgeService service = service(repository);
        service.index("alpha", "knowledge", "private.md", false, false);

        assertTrue(service.search("beta", "orchid", 5).isEmpty());
        UUID id = service.sources("alpha", 10).getFirst().id();
        assertFalse(service.delete("beta", id));
        assertTrue(service.delete("alpha", id));
    }

    @Test
    void rejectsUnrelatedLexicalQueriesInsteadOfReturningEveryDocument() throws Exception {
        Files.writeString(root.resolve("architecture.md"), "Minikun uses PostgreSQL for indexed knowledge");
        PersonalKnowledgeService service = service(new InMemoryRepository());
        service.index("default", "knowledge", "architecture.md", false, false);

        assertTrue(service.search("default", "สูตรทำขนมช็อกโกแลต", 5).isEmpty());
    }

    @Test
    void migratesUnchangedDocumentsRetriesMissingVectorsAndRejectsUnrelatedGemmaMatches() throws Exception {
        String model = "embeddinggemma-2:270m-mxfp8-text";
        Files.writeString(root.resolve("architecture.md"), "Minikun stores knowledge in PostgreSQL");
        InMemoryRepository repository = new InMemoryRepository();
        EmbeddingModel old = mock(EmbeddingModel.class);
        when(old.embed(anyString())).thenReturn(new float[] {1, 0});
        service(repository, old, "qwen3-embedding:0.6b").index("default", "knowledge", "architecture.md", false, false);
        EmbeddingModel gemma = mock(EmbeddingModel.class);
        when(gemma.embed(anyString())).thenReturn(new float[] {1, 0});
        PersonalKnowledgeService service = service(repository, gemma, model);
        assertEquals(1, service.index("default", "knowledge", "architecture.md", false, false).indexed());
        assertEquals(1, service.index("default", "knowledge", "architecture.md", false, false).unchanged());
        assertEquals(model, repository.chunks("default", 10).getFirst().embeddingModel());
        when(gemma.embed("task: search result | query: ภูเขาไฟระเบิดได้อย่างไร"))
                .thenReturn(new float[] {.63f, (float) Math.sqrt(1 - .63*.63)});
        assertTrue(service.search("default", "ภูเขาไฟระเบิดได้อย่างไร", 5).isEmpty());
        assertTrue(service.search("default", "PostgreSQL", 5).getFirst().semantic());
        verify(gemma).embed("task: search result | query: PostgreSQL");
        String document = "title: architecture.md | text: Minikun stores knowledge in PostgreSQL";
        verify(gemma).embed(document);
        when(gemma.embed(document)).thenThrow(new IllegalStateException("outage"));
        service.index("default", "knowledge", "architecture.md", false, true);
        assertTrue(repository.chunks("default", 10).getFirst().embedding().isBlank());
        doReturn(new float[] {1, 0}).when(gemma).embed(document);
        assertEquals(1, service.index("default", "knowledge", "architecture.md", false, false).indexed());
    }

    private PersonalKnowledgeService service(InMemoryRepository repository) {
        return service(repository, null, "qwen3-embedding:0.6b");
    }

    private PersonalKnowledgeService service(InMemoryRepository repository, EmbeddingModel model, String name) {
        return new PersonalKnowledgeService(repository,
                new KnowledgeDocumentReader(List.of(new KnowledgeRoot("knowledge", root)), 8192, 20, 4),
                new KnowledgeChunker(500, 50), model, name,
                Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC), 100, 0.85, 0.70);
    }

    private static final class InMemoryRepository implements PersonalKnowledgeRepository {
        private final Map<UUID, KnowledgeSourceRecord> sources = new LinkedHashMap<>();
        private final Map<UUID, List<KnowledgeChunkDraft>> chunks = new LinkedHashMap<>();

        @Override
        public Optional<KnowledgeSourceRecord> find(String ownerId, String root, String path) {
            return sources.values().stream().filter(source -> source.ownerId().equals(ownerId)
                    && source.root().equals(root) && source.path().equals(path)).findFirst();
        }

        @Override
        public boolean embeddingsCurrent(UUID sourceId, String model) {
            List<KnowledgeChunkDraft> values = chunks.getOrDefault(sourceId, List.of());
            return !values.isEmpty() && values.stream().allMatch(c -> !c.embedding().isBlank()
                    && model.equals(c.embeddingModel()));
        }

        @Override
        public UUID begin(String ownerId, String root, String path, String name) {
            UUID id = find(ownerId, root, path).map(KnowledgeSourceRecord::id).orElseGet(UUID::randomUUID);
            KnowledgeSourceRecord old = sources.get(id);
            sources.put(id, new KnowledgeSourceRecord(id, ownerId, root, path, name, "INDEXING",
                    old == null ? "" : old.fileHash(), null, null, old == null ? 0 : old.chunkCount(), ""));
            return id;
        }

        @Override
        public void replace(UUID sourceId, String hash, Instant modifiedAt, Instant indexedAt,
                List<KnowledgeChunkDraft> values) {
            KnowledgeSourceRecord old = sources.get(sourceId);
            sources.put(sourceId, new KnowledgeSourceRecord(sourceId, old.ownerId(), old.root(), old.path(),
                    old.name(), "READY", hash, modifiedAt, indexedAt, values.size(), ""));
            chunks.put(sourceId, List.copyOf(values));
        }

        @Override
        public void fail(UUID sourceId, Instant indexedAt, String error) {
            KnowledgeSourceRecord old = sources.get(sourceId);
            if (old != null) sources.put(sourceId, new KnowledgeSourceRecord(sourceId, old.ownerId(), old.root(),
                    old.path(), old.name(), "ERROR", old.fileHash(), old.modifiedAt(), indexedAt,
                    old.chunkCount(), error));
        }

        @Override
        public List<KnowledgeSourceRecord> list(String ownerId, int limit) {
            return sources.values().stream().filter(source -> source.ownerId().equals(ownerId)).limit(limit).toList();
        }

        @Override
        public List<KnowledgeChunk> chunks(String ownerId, int limit) {
            List<KnowledgeChunk> result = new ArrayList<>();
            for (KnowledgeSourceRecord source : list(ownerId, Integer.MAX_VALUE)) {
                List<KnowledgeChunkDraft> values = chunks.getOrDefault(source.id(), List.of());
                for (KnowledgeChunkDraft value : values) {
                    result.add(new KnowledgeChunk(UUID.randomUUID(), source.id(), source.root(), source.path(),
                            source.name(), value.index(), value.heading(), value.content(), value.embedding(),
                            value.embeddingModel()));
                }
            }
            return result.stream().limit(limit).toList();
        }

        @Override
        public boolean delete(String ownerId, UUID sourceId) {
            KnowledgeSourceRecord source = sources.get(sourceId);
            if (source == null || !source.ownerId().equals(ownerId)) return false;
            sources.remove(sourceId);
            chunks.remove(sourceId);
            return true;
        }

        @Override public int sourceCount(String ownerId) { return list(ownerId, Integer.MAX_VALUE).size(); }
        @Override public int chunkCount(String ownerId) { return chunks(ownerId, Integer.MAX_VALUE).size(); }
    }
}
