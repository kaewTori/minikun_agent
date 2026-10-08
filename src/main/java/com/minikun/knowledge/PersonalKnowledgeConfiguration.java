package com.minikun.knowledge;

import java.time.Clock;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.minikun.tools.PersonalKnowledgeTool;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "minikun.personal-knowledge.enabled", havingValue = "true", matchIfMissing = true)
@Import({PersonalKnowledgeController.class, PersonalKnowledgeExceptionHandler.class,
        PersonalKnowledgeTool.class, PersonalKnowledgeScheduler.class, PersonalKnowledgeHealthIndicator.class})
public class PersonalKnowledgeConfiguration {
    @Bean
    PersonalKnowledgeRepository personalKnowledgeRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        return new JdbcPersonalKnowledgeRepository(jdbc, new TransactionTemplate(transactionManager));
    }

    @Bean
    PersonalKnowledgeService personalKnowledgeService(
            PersonalKnowledgeRepository repository,
            ObjectProvider<EmbeddingModel> embeddingModels,
            Clock memoryClock,
            @Value("${minikun.personal-knowledge.roots:}") String roots,
            @Value("${minikun.personal-knowledge.max-file-bytes:2097152}") long maxFileBytes,
            @Value("${minikun.personal-knowledge.max-files:500}") int maxFiles,
            @Value("${minikun.personal-knowledge.max-depth:8}") int maxDepth,
            @Value("${minikun.personal-knowledge.chunk.characters:1200}") int chunkCharacters,
            @Value("${minikun.personal-knowledge.chunk.overlap:160}") int chunkOverlap,
            @Value("${minikun.personal-knowledge.retrieval.max-candidates:2000}") int maxCandidates,
            @Value("${minikun.personal-knowledge.semantic.weight:0.85}") double semanticWeight,
            @Value("${minikun.personal-knowledge.retrieval.minimum-score:0.70}") double minimumScore,
            @Value("${spring.ai.ollama.embedding.options.model:embeddinggemma-2:270m-mxfp8-text}") String embeddingModelName) {
        return new PersonalKnowledgeService(repository,
                new KnowledgeDocumentReader(KnowledgeRoot.parseList(roots), maxFileBytes, maxFiles, maxDepth),
                new KnowledgeChunker(chunkCharacters, chunkOverlap), embeddingModels.getIfAvailable(),
                embeddingModelName, memoryClock, maxCandidates, semanticWeight, minimumScore);
    }
}
