package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.task.TaskModelProvider;
import com.minikun.search.SearchDecisionClientException;
import com.minikun.search.model.SearchDecisionPrompt;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TaskModelSearchDecisionProviderTest {
    private static final SearchDecisionPrompt PROMPT = new SearchDecisionPrompt(
            "Classify search intent", "2026-08-29", "ทดสอบ");

    @Test
    void normalizesFalseCurrentInformationToSearch() {
        TaskModelProvider model = request ->
                "{\"shouldSearch\":false,\"reason\":\"CURRENT_INFORMATION\"}";
        var provider = new TaskModelSearchDecisionProvider(
                model, new ObjectMapper(), Duration.ofSeconds(1));

        assertTrue(provider.classify(PROMPT).shouldSearch());
    }

    @Test
    void normalizesTrueGeneralKnowledgeToNoSearch() {
        TaskModelProvider model = request ->
                "{\"shouldSearch\":true,\"reason\":\"GENERAL_KNOWLEDGE\"}";
        var provider = new TaskModelSearchDecisionProvider(
                model, new ObjectMapper(), Duration.ofSeconds(1));

        assertFalse(provider.classify(PROMPT).shouldSearch());
    }

    @Test
    void rejectsReasonsReservedForOtherPipelineStages() {
        TaskModelProvider model = request ->
                "{\"shouldSearch\":true,\"reason\":\"IMAGE_REQUEST\"}";
        var provider = new TaskModelSearchDecisionProvider(
                model, new ObjectMapper(), Duration.ofSeconds(1));

        assertThrows(SearchDecisionClientException.class, () -> provider.classify(PROMPT));
    }
}
