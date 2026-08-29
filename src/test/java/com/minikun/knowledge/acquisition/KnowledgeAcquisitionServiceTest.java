package com.minikun.knowledge.acquisition;

import static com.minikun.knowledge.acquisition.KnowledgeAcquisitionModels.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.research.AutonomousResearchResult;
import com.minikun.research.AutonomousResearchService;
import com.minikun.research.ResearchStopReason;
import com.minikun.research.ResearchTrace;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeAcquisitionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void keepsInternalEmbeddingOutOfManagementApiJson() throws Exception {
        assertTrue(Claim.class.getMethod("embedding")
                .isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class));
    }

    @Test
    void publishesTrustedRenderedClaimAndMakesItRetrievable() {
        KnowledgeAcquisitionStore store = new KnowledgeAcquisitionStore(null, new ObjectMapper());
        AcquiredKnowledgeIndex index = new AcquiredKnowledgeIndex(store, null, "", CLOCK, 100, .85, .2);
        KnowledgeClaimExtractor extractor = (topic, evidence) -> List.of(new ClaimDraft(
                "Spring AI supports portable model APIs.", List.of(0), .91,
                ExtractionMethod.MODEL));
        KnowledgeAcquisitionService service = service(store, index, extractor, research(List.of(
                candidate("official", "https://docs.spring.io/spring-ai/reference/", KnowledgeSource.BROWSER))));
        Topic topic = service.createTopic("default", "Spring AI", "Track stable Spring AI capabilities",
                TopicOrigin.SUBSCRIBED, 80, RefreshPolicy.WEEKLY, SourcePolicy.OFFICIAL_FIRST,
                List.of("docs.spring.io"), TopicStatus.ACTIVE);

        AcquisitionRun run = service.runNow("default", topic.id());

        assertEquals(RunStatus.COMPLETED, run.status());
        assertEquals(1, run.publishedCount());
        assertEquals(ClaimStatus.PUBLISHED, service.claims("default", null, 10).getFirst().status());
        KnowledgeContext recalled = index.recall("default", "Spring AI model APIs", 5);
        assertFalse(recalled.candidates().isEmpty());
        assertTrue(recalled.content().contains("Verified acquired knowledge"));
        assertEquals("https://docs.spring.io/spring-ai/reference/",
                recalled.candidates().getFirst().provenance());
    }

    @Test
    void keepsFallbackAndSnippetOnlyKnowledgeOutOfPublishedRetrieval() {
        KnowledgeAcquisitionStore store = new KnowledgeAcquisitionStore(null, new ObjectMapper());
        AcquiredKnowledgeIndex index = new AcquiredKnowledgeIndex(store, null, "", CLOCK, 100, .85, .1);
        KnowledgeClaimExtractor fallback = (topic, evidence) -> List.of(new ClaimDraft(
                "An unverified search excerpt.", List.of(0), .9, ExtractionMethod.FALLBACK));
        KnowledgeAcquisitionService service = service(store, index, fallback, research(List.of(
                candidate("snippet", "https://example.com/result", KnowledgeSource.SEARCH))));
        Topic topic = service.createTopic("default", "Example topic", "Find example facts",
                null, null, RefreshPolicy.DAILY, SourcePolicy.BALANCED, List.of(), null);

        AcquisitionRun run = service.runNow("default", topic.id());

        assertEquals(0, run.publishedCount());
        assertEquals(ClaimStatus.CANDIDATE, service.claims("default", null, 10).getFirst().status());
        assertTrue(index.recall("default", "unverified search excerpt", 5).candidates().isEmpty());
    }

    @Test
    void requiresTwoRenderedDomainsForUntrustedAutomaticPublication() {
        Topic topic = new Topic(java.util.UUID.randomUUID(), "default", "Spring AI", "Track Spring AI model APIs",
                TopicOrigin.SUBSCRIBED, 50, RefreshPolicy.DAILY, SourcePolicy.BALANCED, List.of(),
                TopicStatus.ACTIVE, NOW, null, NOW, NOW);
        KnowledgeClaimVerifier verifier = new KnowledgeClaimVerifier(.7);
        ClaimDraft draft = new ClaimDraft("Spring AI supports portable model APIs.", List.of(0, 1), .9,
                ExtractionMethod.MODEL);

        Verification oneRendered = verifier.verify(topic, draft, List.of(
                candidate("page", "https://one.example/page", KnowledgeSource.BROWSER),
                candidate("snippet", "https://two.example/page", KnowledgeSource.SEARCH)));
        Verification twoRendered = verifier.verify(topic, draft, List.of(
                candidate("page-one", "https://one.example/page", KnowledgeSource.BROWSER),
                candidate("page-two", "https://two.example/page", KnowledgeSource.BROWSER)));

        assertEquals(ClaimStatus.CANDIDATE, oneRendered.status());
        assertEquals(ClaimStatus.PUBLISHED, twoRendered.status());
    }

    @Test
    void rejectsATrustedRenderedPageWhoseContentDoesNotGroundTheClaim() {
        Topic topic = new Topic(java.util.UUID.randomUUID(), "default", "Spring AI", "Track capabilities",
                TopicOrigin.SUBSCRIBED, 50, RefreshPolicy.DAILY, SourcePolicy.OFFICIAL_ONLY,
                List.of("docs.spring.io"), TopicStatus.ACTIVE, NOW, null, NOW, NOW);
        KnowledgeClaimVerifier verifier = new KnowledgeClaimVerifier(.7);
        ClaimDraft draft = new ClaimDraft(
                "Spring AI provides portable APIs for Anthropic, OpenAI, and Microsoft models.",
                List.of(0), .9, ExtractionMethod.MODEL);
        KnowledgeCandidate unrelated = new KnowledgeCandidate("unrelated", KnowledgeSource.BROWSER,
                "Spring Framework integration testing checks IoC wiring and JDBC data access.", 0,
                "https://docs.spring.io/spring-framework/reference/testing.html");

        Verification result = verifier.verify(topic, draft, List.of(unrelated));

        assertEquals(ClaimStatus.CANDIDATE, result.status());
        assertTrue(result.reason().contains("not an extractive span"));
    }

    @Test
    void doesNotTreatATrustedSnippetAsATrustedRenderedSource() {
        Topic topic = new Topic(java.util.UUID.randomUUID(), "default", "Spring AI", "Track capabilities",
                TopicOrigin.SUBSCRIBED, 50, RefreshPolicy.DAILY, SourcePolicy.OFFICIAL_ONLY,
                List.of("docs.spring.io"), TopicStatus.ACTIVE, NOW, null, NOW, NOW);
        KnowledgeClaimVerifier verifier = new KnowledgeClaimVerifier(.7);
        ClaimDraft draft = new ClaimDraft("Spring AI supports portable model APIs.", List.of(0, 1), .9,
                ExtractionMethod.MODEL);
        List<KnowledgeCandidate> evidence = List.of(
                new KnowledgeCandidate("rendered", KnowledgeSource.BROWSER,
                        "Spring AI supports portable model APIs.", 0, "https://example.com/page"),
                new KnowledgeCandidate("trusted-snippet", KnowledgeSource.SEARCH,
                        "Spring AI supports portable model APIs.", 1,
                        "https://docs.spring.io/spring-ai/reference/index.html"));

        Verification result = verifier.verify(topic, draft, evidence);

        assertEquals(ClaimStatus.CANDIDATE, result.status());
        assertTrue(result.reason().contains("configured trusted domains"));
    }

    @Test
    void rejectsAnExtractivePageFromAnUnrelatedProjectOnTheSameTrustedDomain() {
        Topic topic = new Topic(java.util.UUID.randomUUID(), "default", "Spring AI", "Track Spring AI capabilities",
                TopicOrigin.SUBSCRIBED, 50, RefreshPolicy.DAILY, SourcePolicy.OFFICIAL_ONLY,
                List.of("docs.spring.io"), TopicStatus.ACTIVE, NOW, null, NOW, NOW);
        KnowledgeClaimVerifier verifier = new KnowledgeClaimVerifier(.7);
        ClaimDraft draft = new ClaimDraft(
                "Spring Framework provides integration testing support for IoC and JDBC.", List.of(0), .9,
                ExtractionMethod.MODEL);
        KnowledgeCandidate unrelated = new KnowledgeCandidate("spring-framework", KnowledgeSource.BROWSER,
                "Spring Framework provides integration testing support for IoC and JDBC.", 0,
                "https://docs.spring.io/spring-framework/reference/testing.html");

        Verification result = verifier.verify(topic, draft, List.of(unrelated));

        assertEquals(ClaimStatus.CANDIDATE, result.status());
        assertTrue(result.reason().contains("not relevant to the topic objective"));
    }

    @Test
    void keepsTerseClassAndPackageHeadingsOutOfPublishedKnowledge() {
        Topic topic = new Topic(java.util.UUID.randomUUID(), "default", "Spring AI", "Track Spring AI capabilities",
                TopicOrigin.SUBSCRIBED, 50, RefreshPolicy.WEEKLY, SourcePolicy.OFFICIAL_ONLY,
                List.of("docs.spring.io"), TopicStatus.ACTIVE, NOW, null, NOW, NOW);
        KnowledgeClaimVerifier verifier = new KnowledgeClaimVerifier(.7);
        ClaimDraft draft = new ClaimDraft("AbstractMcpAnnotatedBeans", List.of(0), .9,
                ExtractionMethod.MODEL);
        KnowledgeCandidate evidence = new KnowledgeCandidate("api", KnowledgeSource.BROWSER,
                "AbstractMcpAnnotatedBeans", 0,
                "https://docs.spring.io/spring-ai/docs/current/api/allclasses-index.html");

        Verification result = verifier.verify(topic, draft, List.of(evidence));

        assertEquals(ClaimStatus.CANDIDATE, result.status());
        assertTrue(result.reason().contains("too terse"));
    }

    @Test
    void requiresExplicitAdvisorySignalForSecurityTopics() {
        Topic topic = new Topic(java.util.UUID.randomUUID(), "default", "Homelab security advisories",
                "Track actionable security advisories affecting Spring Boot",
                TopicOrigin.SYSTEM_DEPENDENCY, 100, RefreshPolicy.DAILY, SourcePolicy.OFFICIAL_FIRST,
                List.of("docs.spring.io"), TopicStatus.ACTIVE, NOW, null, NOW, NOW);
        KnowledgeClaimVerifier verifier = new KnowledgeClaimVerifier(.7);
        String text = "Spring Authorization Server can be used anywhere you already use Spring Security";
        ClaimDraft draft = new ClaimDraft(text, List.of(0), .9, ExtractionMethod.MODEL);
        KnowledgeCandidate evidence = new KnowledgeCandidate("generic-security", KnowledgeSource.BROWSER,
                text, 0, "https://docs.spring.io/spring-authorization-server/reference/getting-started.html");

        Verification result = verifier.verify(topic, draft, List.of(evidence));

        assertEquals(ClaimStatus.CANDIDATE, result.status());
        assertTrue(result.reason().contains("requires an advisory"));
    }

    @Test
    void explicitReviewCanRestoreAPreviouslyRetractedClaim() {
        KnowledgeAcquisitionStore store = new KnowledgeAcquisitionStore(null, new ObjectMapper());
        AcquiredKnowledgeIndex index = new AcquiredKnowledgeIndex(store, null, "", CLOCK, 100, .85, .1);
        KnowledgeClaimExtractor extractor = (topic, evidence) -> List.of(new ClaimDraft(
                "A fact requiring human review.", List.of(0), .9, ExtractionMethod.FALLBACK));
        KnowledgeAcquisitionService service = service(store, index, extractor, research(List.of(
                candidate("page", "https://example.com/page", KnowledgeSource.BROWSER))));
        Topic topic = service.createTopic("default", "Review", "Review claims", null, null,
                RefreshPolicy.WEEKLY, SourcePolicy.BALANCED, List.of(), null);
        service.runNow("default", topic.id());
        Claim candidate = service.claims("default", null, 10).getFirst();

        Claim retracted = service.reviewClaim("default", candidate.id(), ClaimStatus.RETRACTED);
        Claim restored = service.reviewClaim("default", candidate.id(), ClaimStatus.PUBLISHED);

        assertEquals(ClaimStatus.RETRACTED, retracted.status());
        assertEquals(ClaimStatus.PUBLISHED, restored.status());
        assertFalse(index.recall("default", "fact requiring human review", 5).candidates().isEmpty());
    }

    @Test
    void officialOnlyTopicRequiresAnExplicitTrustedDomain() {
        KnowledgeAcquisitionStore store = new KnowledgeAcquisitionStore(null, new ObjectMapper());
        AcquiredKnowledgeIndex index = new AcquiredKnowledgeIndex(store, null, "", CLOCK, 100, .85, .1);
        KnowledgeAcquisitionService service = service(store, index, (topic, evidence) -> List.of(),
                research(List.of()));

        assertThrows(IllegalArgumentException.class, () -> service.createTopic(
                "default", "Official", "Official sources only", null, null, RefreshPolicy.WEEKLY,
                SourcePolicy.OFFICIAL_ONLY, List.of(), null));
    }

    private KnowledgeAcquisitionService service(KnowledgeAcquisitionStore store, AcquiredKnowledgeIndex index,
            KnowledgeClaimExtractor extractor, AutonomousResearchService research) {
        KnowledgeAcquisitionAgent agent = new KnowledgeAcquisitionAgent(store, research, extractor,
                new KnowledgeClaimVerifier(.7), index, CLOCK, Duration.ofSeconds(30), 8, 5, true);
        return new KnowledgeAcquisitionService(store, agent, CLOCK);
    }

    private AutonomousResearchService research(List<KnowledgeCandidate> evidence) {
        return request -> new AutonomousResearchResult(KnowledgeContext.empty(), evidence,
                new ResearchTrace(request.userQuery(), List.of("What changed?"), List.of(request.primaryQuery()),
                        1, ResearchStopReason.SUFFICIENT, List.of(), true));
    }

    private KnowledgeCandidate candidate(String id, String url, KnowledgeSource source) {
        return new KnowledgeCandidate(id, source,
                "Spring AI supports portable model APIs. This is rendered source evidence.", 0, url);
    }
}
