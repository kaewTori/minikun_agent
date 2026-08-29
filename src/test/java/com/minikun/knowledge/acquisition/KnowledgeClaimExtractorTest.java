package com.minikun.knowledge.acquisition;

import static com.minikun.knowledge.acquisition.KnowledgeAcquisitionModels.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.task.TaskModelProvider;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KnowledgeClaimExtractorTest {

    @Test
    void extractsClaimsFromTheExpectedSchema() {
        KnowledgeClaimExtractor extractor = extractor("""
                {"claims":[{"text":"Spring AI has a fluent ChatClient API.","evidence":[0],"confidence":0.9}]}
                """);

        List<ClaimDraft> claims = extractor.extract(topic(), evidence());

        assertEquals(1, claims.size());
        assertEquals("Spring AI has a fluent ChatClient API.", claims.getFirst().text());
        assertEquals(List.of(0), claims.getFirst().evidenceIndexes());
        assertEquals(.9, claims.getFirst().confidence());
        assertEquals(ExtractionMethod.MODEL, claims.getFirst().extractionMethod());
    }

    @Test
    void safelyRepairsFieldsSplitIntoAdjacentObjectsByASmallModel() {
        KnowledgeClaimExtractor extractor = extractor("""
                {"claims":[{"text":"Spring AI supports streaming."},{"evidence":["0"],"confidence":"0.82"}]}
                """);

        List<ClaimDraft> claims = extractor.extract(topic(), evidence());

        assertEquals(1, claims.size());
        assertEquals("Spring AI supports streaming.", claims.getFirst().text());
        assertEquals(List.of(0), claims.getFirst().evidenceIndexes());
        assertEquals(.82, claims.getFirst().confidence());
        assertEquals(ExtractionMethod.MODEL, claims.getFirst().extractionMethod());
    }

    @Test
    void acceptsASingleClaimObjectNestedUnderOutput() {
        KnowledgeClaimExtractor extractor = extractor("""
                {"output":{"claim":"Spring AI supports streaming.","source":0,"confidence":0.8}}
                """);

        List<ClaimDraft> claims = extractor.extract(topic(), evidence());

        assertEquals(1, claims.size());
        assertEquals(List.of(0), claims.getFirst().evidenceIndexes());
        assertEquals(.8, claims.getFirst().confidence());
        assertEquals(ExtractionMethod.MODEL, claims.getFirst().extractionMethod());
    }

    @Test
    void continuesWithTheNextBoundedSourceWhenOneSchemaIsUnusable() {
        AtomicInteger calls = new AtomicInteger();
        TaskModelProvider provider = request -> calls.incrementAndGet() == 1
                ? "{\"summary\":\"schema drift\"}"
                : "{\"claims\":[{\"text\":\"Another source fact.\","
                        + "\"evidence\":[0],\"confidence\":0.8}]}";
        KnowledgeClaimExtractor extractor = new TaskModelKnowledgeClaimExtractor(provider, new ObjectMapper());

        List<KnowledgeCandidate> evidence = List.of(
                evidence().getFirst(),
                new KnowledgeCandidate("second", KnowledgeSource.BROWSER,
                        "Another source fact.", 1, "https://docs.spring.io/second"));
        List<ClaimDraft> claims = extractor.extract(topic(), evidence);

        assertEquals(2, calls.get());
        assertEquals(1, claims.size());
        assertEquals(List.of(1), claims.getFirst().evidenceIndexes());
        assertEquals(ExtractionMethod.MODEL, claims.getFirst().extractionMethod());
    }

    @Test
    void quarantinesEvidenceWhenTheModelReturnsNoUsableClaims() {
        KnowledgeClaimExtractor extractor = extractor("{\"claims\":[{\"text\":\"Unsupported\"}]}");

        List<ClaimDraft> claims = extractor.extract(topic(), evidence());

        assertEquals(1, claims.size());
        assertEquals(.35, claims.getFirst().confidence());
        assertEquals(ExtractionMethod.FALLBACK, claims.getFirst().extractionMethod());
        assertTrue(claims.getFirst().text().startsWith("Spring AI reference documentation"));
    }

    private KnowledgeClaimExtractor extractor(String response) {
        TaskModelProvider provider = request -> response;
        return new TaskModelKnowledgeClaimExtractor(provider, new ObjectMapper());
    }

    private Topic topic() {
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        return new Topic(UUID.randomUUID(), "default", "Spring AI", "Track stable Spring AI capabilities",
                TopicOrigin.SUBSCRIBED, 80, RefreshPolicy.MANUAL, SourcePolicy.OFFICIAL_ONLY,
                List.of("docs.spring.io"), TopicStatus.ACTIVE, null, null, now, now);
    }

    private List<KnowledgeCandidate> evidence() {
        return List.of(new KnowledgeCandidate("official", KnowledgeSource.BROWSER,
                "Spring AI reference documentation describes the fluent ChatClient API and streaming support.",
                0, "https://docs.spring.io/spring-ai/reference/api/chatclient.html"));
    }
}
