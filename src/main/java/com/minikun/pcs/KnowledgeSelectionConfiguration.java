package com.minikun.pcs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ai.chat.model.ChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class KnowledgeSelectionConfiguration {
    @Bean
    @ConditionalOnProperty(
            value = "minikun.knowledge-ranking.ai.enabled",
            havingValue = "true")
    KnowledgeRankingService aiKnowledgeRankingService(ChatModel chatModel, ObjectMapper objectMapper) {
        return new AiKnowledgeRankingService(chatModel, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeRankingService.class)
    KnowledgeRankingService knowledgeRankingService() {
        return new DefaultKnowledgeRankingService();
    }

    @Bean
    KnowledgeSelectionService knowledgeSelectionService(KnowledgeRankingService rankingService) {
        return new DefaultKnowledgeSelectionService(rankingService);
    }
}
