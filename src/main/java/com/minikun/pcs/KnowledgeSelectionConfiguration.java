package com.minikun.pcs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.minikun.model.task.TaskModelProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeSelectionProperties.class)
public class KnowledgeSelectionConfiguration {
    @Bean
    KnowledgeRelevancePolicy knowledgeRelevancePolicy(
            @Value("${minikun.knowledge-relevance.enabled:false}") boolean enabled,
            @Value("${minikun.knowledge-relevance.minimum-score:0.0}") double minimumScore) {
        return new KnowledgeRelevancePolicy(enabled, minimumScore);
    }

    @Bean
    @ConditionalOnProperty(value = "minikun.knowledge-relevance.ai.enabled", havingValue = "true")
    KnowledgeRelevanceService aiKnowledgeRelevanceService(
            TaskModelProvider taskModelProvider,
            ObjectMapper objectMapper,
            KnowledgeRelevancePolicy policy,
            @Value("${minikun.knowledge-relevance.ai.minimum-candidates:6}") int minimumCandidates) {
        return new ThresholdKnowledgeRelevanceService(
                new AiKnowledgeRelevanceService(taskModelProvider, objectMapper, policy),
                new DefaultKnowledgeRelevanceService(policy), minimumCandidates);
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeRelevanceService.class)
    KnowledgeRelevanceService knowledgeRelevanceService(KnowledgeRelevancePolicy policy) {
        return new DefaultKnowledgeRelevanceService(policy);
    }

    @Bean
    @ConditionalOnProperty(value = "minikun.knowledge-ranking.ai.enabled", havingValue = "true")
    KnowledgeRankingService aiKnowledgeRankingService(
            TaskModelProvider taskModelProvider,
            ObjectMapper objectMapper,
            @Value("${minikun.knowledge-ranking.ai.minimum-candidates:6}") int minimumCandidates) {
        return new ThresholdKnowledgeRankingService(
                new AiKnowledgeRankingService(taskModelProvider, objectMapper), minimumCandidates);
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeRankingService.class)
    KnowledgeRankingService knowledgeRankingService() {
        return new DefaultKnowledgeRankingService();
    }

    @Bean
    KnowledgeSelectionPolicy knowledgeSelectionPolicy(KnowledgeSelectionProperties properties) {
        return properties.toPolicy();
    }

    @Bean
    KnowledgeSelectionService knowledgeSelectionService(
            KnowledgeRankingService rankingService,
            KnowledgeRelevanceService relevanceService,
            KnowledgeSelectionPolicy policy) {
        return new DefaultKnowledgeSelectionService(
                rankingService, policy, relevanceService);
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeConsolidationService.class)
    KnowledgeConsolidationService knowledgeConsolidationService() {
        return new DefaultKnowledgeConsolidationService();
    }
}
