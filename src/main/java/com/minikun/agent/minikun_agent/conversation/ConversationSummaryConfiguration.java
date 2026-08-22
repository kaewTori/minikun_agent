package com.minikun.agent.minikun_agent.conversation;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.minikun.model.task.TaskModelProvider;

import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
public class ConversationSummaryConfiguration {
    @Bean
    ConversationSummaryStore conversationSummaryStore(ObjectProvider<JdbcTemplate> jdbcTemplate) {
        return new ResilientConversationSummaryStore(jdbcTemplate);
    }

    @Bean
    ConversationSummaryGenerator conversationSummaryGenerator(
            TaskModelProvider taskModelProvider,
            @Value("${minikun.conversation-summary.max-output-tokens:384}") int maximumOutputTokens) {
        return new TaskModelConversationSummaryGenerator(taskModelProvider, maximumOutputTokens);
    }

    @Bean
    ConversationSummaryService conversationSummaryService(
            ConversationSummaryStore store,
            ConversationSummaryGenerator generator,
            Clock memoryClock,
            @Value("${minikun.conversation-summary.enabled:true}") boolean enabled,
            @Value("${minikun.conversation-summary.minimum-history-messages:10}") int minimumHistoryMessages,
            @Value("${minikun.conversation-summary.retain-recent-messages:8}") int retainedRecentMessages,
            @Value("${minikun.conversation-summary.max-characters:1800}") int maximumSummaryCharacters,
            @Value("${minikun.conversation-summary.max-tracked-fingerprints:128}") int maximumTrackedFingerprints,
            @Value("${minikun.conversation-summary.queue-capacity:16}") int queueCapacity,
            MeterRegistry meterRegistry) {
        return new ConversationSummaryService(store, generator, memoryClock, enabled,
                minimumHistoryMessages, retainedRecentMessages, maximumSummaryCharacters,
                maximumTrackedFingerprints, queueCapacity, meterRegistry);
    }
}
