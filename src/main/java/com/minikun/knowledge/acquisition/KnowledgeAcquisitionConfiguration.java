package com.minikun.knowledge.acquisition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.task.TaskModelProvider;
import com.minikun.research.AutonomousResearchService;
import java.time.Clock;
import java.time.Duration;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "minikun.knowledge-acquisition.enabled", havingValue = "true", matchIfMissing = true)
@Import({KnowledgeAcquisitionController.class, KnowledgeAcquisitionExceptionHandler.class})
public class KnowledgeAcquisitionConfiguration {
    @Bean
    KnowledgeAcquisitionStore knowledgeAcquisitionStore(
            ObjectProvider<JdbcTemplate> jdbc, ObjectMapper objectMapper) {
        return new KnowledgeAcquisitionStore(jdbc.getIfAvailable(), objectMapper);
    }

    @Bean
    KnowledgeClaimExtractor knowledgeClaimExtractor(TaskModelProvider taskModelProvider, ObjectMapper objectMapper) {
        return new TaskModelKnowledgeClaimExtractor(taskModelProvider, objectMapper);
    }

    @Bean
    KnowledgeClaimVerifier knowledgeClaimVerifier(
            @Value("${minikun.knowledge-acquisition.verification.minimum-confidence:0.7}")
            double minimumConfidence) {
        return new KnowledgeClaimVerifier(minimumConfidence);
    }

    @Bean
    AcquiredKnowledgeIndex acquiredKnowledgeIndex(KnowledgeAcquisitionStore store,
            ObjectProvider<EmbeddingModel> embeddings,
            @Value("${spring.ai.ollama.embedding.options.model:embeddinggemma-2:270m-mxfp8-text}") String embeddingModelName,
            Clock memoryClock,
            @Value("${minikun.knowledge-acquisition.retrieval.max-candidates:2000}") int maximumCandidates,
            @Value("${minikun.knowledge-acquisition.retrieval.semantic-weight:0.85}") double semanticWeight,
            @Value("${minikun.knowledge-acquisition.retrieval.minimum-score:0.70}") double minimumScore) {
        return new AcquiredKnowledgeIndex(store, embeddings.getIfAvailable(), embeddingModelName, memoryClock,
                maximumCandidates, semanticWeight, minimumScore);
    }

    @Bean
    KnowledgeAcquisitionAgent knowledgeAcquisitionAgent(KnowledgeAcquisitionStore store,
            AutonomousResearchService research, KnowledgeClaimExtractor extractor,
            KnowledgeClaimVerifier verifier, AcquiredKnowledgeIndex index, Clock memoryClock,
            @Value("${minikun.knowledge-acquisition.run.timeout:PT60S}") Duration timeout,
            @Value("${minikun.knowledge-acquisition.run.result-limit:8}") int resultLimit,
            @Value("${minikun.knowledge-acquisition.run.source-read-limit:5}") int sourceReadLimit,
            @Value("${minikun.knowledge-acquisition.safe-search:true}") boolean safeSearch) {
        return new KnowledgeAcquisitionAgent(store, research, extractor, verifier, index, memoryClock,
                timeout, resultLimit, sourceReadLimit, safeSearch);
    }

    @Bean
    KnowledgeAcquisitionService knowledgeAcquisitionService(
            KnowledgeAcquisitionStore store, KnowledgeAcquisitionAgent agent, Clock memoryClock) {
        return new KnowledgeAcquisitionService(store, agent, memoryClock);
    }

    @Bean
    @ConditionalOnProperty(name = "minikun.knowledge-acquisition.scheduler.enabled",
            havingValue = "true", matchIfMissing = true)
    KnowledgeAcquisitionScheduler knowledgeAcquisitionScheduler(KnowledgeAcquisitionService service,
            @Value("${minikun.knowledge-acquisition.owner-id:default}") String ownerId,
            @Value("${minikun.knowledge-acquisition.scheduler.topics-per-run:1}") int topicsPerRun) {
        return new KnowledgeAcquisitionScheduler(service, ownerId, topicsPerRun);
    }
}
