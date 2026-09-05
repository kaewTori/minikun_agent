package com.minikun.agent.minikun_agent.conversation;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Provides ephemeral conversation memory when the PostgreSQL volume is unavailable. */
@Configuration(proxyBeanMethods = false)
@Profile("database-offline")
class DatabaseOfflineConfiguration {
    @Bean
    ChatMemory chatMemory(
            @Value("${spring.ai.chat.memory.max-messages:20}") int maximumMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(maximumMessages)
                .build();
    }
}
