package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.pcs.SearchContext;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import org.junit.jupiter.api.Test;

class DefaultSearchContextAwarenessServiceTest {
    private final DefaultSearchContextAwarenessService service =
            new DefaultSearchContextAwarenessService();

    @Test
    void observesSuppliedFactsWithoutReexecutingSearch() {
        SearchContext context = service.observe(
                "  Latest\tNews  ", true, new KnowledgeContext("memory"),
                new SearchDecision(true, "Latest News", SearchDecisionReason.CURRENT_INFORMATION),
                true, new KnowledgeContext("search"));

        assertEquals("  Latest\tNews  ", context.userQuery());
        assertTrue(context.conversationContextAvailable());
        assertTrue(context.memoryKnowledgeAvailable());
        assertTrue(context.searchDecisionAvailable());
        assertTrue(context.searchRequested());
        assertTrue(context.searchAttempted());
        assertTrue(context.searchKnowledgeAvailable());
        assertEquals(SearchDecisionReason.CURRENT_INFORMATION, context.searchDecisionReason());
    }

    @Test
    void recordsAbsenceAndDisabledSearchWithoutSearchWork() {
        SearchContext context = service.observe(
                "query", false, new KnowledgeContext(""), null, false, null);

        assertEquals("query", context.userQuery());
        assertFalse(context.conversationContextAvailable());
        assertFalse(context.memoryKnowledgeAvailable());
        assertFalse(context.searchDecisionAvailable());
        assertFalse(context.searchRequested());
        assertFalse(context.searchAttempted());
        assertFalse(context.searchKnowledgeAvailable());
        assertEquals(SearchDecisionReason.GENERAL_KNOWLEDGE, context.searchDecisionReason());
    }

    @Test
    void equivalentInputsProduceEqualFreshContexts() {
        SearchDecision decision = new SearchDecision(
                false, "query", SearchDecisionReason.GENERAL_KNOWLEDGE);

        SearchContext first = service.observe(
                "query", true, new KnowledgeContext("memory"), decision, false, null);
        SearchContext second = service.observe(
                new String("query"), true, new KnowledgeContext("memory"),
                new SearchDecision(false, "query", SearchDecisionReason.GENERAL_KNOWLEDGE),
                false, new KnowledgeContext(""));

        assertEquals(first, second);
    }
}
