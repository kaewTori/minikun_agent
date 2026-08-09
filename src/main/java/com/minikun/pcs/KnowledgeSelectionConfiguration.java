package com.minikun.pcs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

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
            ChatModel chatModel,
            ObjectMapper objectMapper,
            KnowledgeRelevancePolicy policy) {
        return new AiKnowledgeRelevanceService(chatModel, objectMapper, policy);
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeRelevanceService.class)
    KnowledgeRelevanceService knowledgeRelevanceService(KnowledgeRelevancePolicy policy) {
        return new DefaultKnowledgeRelevanceService(policy);
    }

    @Bean
    @ConditionalOnProperty(value = "minikun.knowledge-ranking.ai.enabled", havingValue = "true")
    KnowledgeRankingService aiKnowledgeRankingService(
            @Value("${minikun.memory.model:${spring.ai.ollama.chat.options.model:main-model}}") String model,
            @Value("${spring.ai.ollama.base-url:http://127.0.0.1:11434}") String baseUrl,
            @Value("${minikun.memory.main-model.timeout:240s}") Duration timeout,
            ObjectMapper objectMapper) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
        return new AiKnowledgeRankingService(ollamaApi, model, objectMapper);
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
