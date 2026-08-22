package com.minikun.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.browser.BrowserContentService;
import com.minikun.model.task.TaskModelProvider;
import com.minikun.search.SearchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ResearchConfiguration {
    @Bean
    ResearchReasoningProvider researchReasoningProvider(
            TaskModelProvider taskModelProvider,
            ObjectMapper objectMapper) {
        return new TaskModelResearchReasoningProvider(taskModelProvider, objectMapper);
    }

    @Bean
    AutonomousResearchService autonomousResearchService(
            SearchService searchService,
            BrowserContentService browserContentService,
            ResearchReasoningProvider reasoningProvider,
            @Value("${minikun.research.autonomous.enabled:true}") boolean enabled,
            @Value("${minikun.research.autonomous.max-iterations:3}") int maximumIterations,
            @Value("${minikun.research.autonomous.max-queries:8}") int maximumQueries,
            @Value("${minikun.research.autonomous.max-follow-up-queries:3}") int maximumFollowUpQueries,
            @Value("${minikun.research.autonomous.evaluation-max-characters:12000}")
            int maximumEvidenceCharacters) {
        return new DefaultAutonomousResearchService(
                searchService, browserContentService, reasoningProvider, enabled,
                maximumIterations, maximumQueries, maximumFollowUpQueries, maximumEvidenceCharacters);
    }
}
