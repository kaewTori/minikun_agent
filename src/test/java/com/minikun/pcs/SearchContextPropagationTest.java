package com.minikun.pcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.minikun.search.model.SearchDecisionReason;
import org.junit.jupiter.api.Test;

class SearchContextPropagationTest {
    @Test
    void selectionContextCarriesSearchContextWithoutChangingSelectionInputs() {
        SearchContext searchContext = new SearchContext(
                "latest news", true, true, true, true, true, true,
                SearchDecisionReason.CURRENT_INFORMATION);

        McsSelectionContext context = new SelectionContextFactory()
                .create("message", "history", SearchSelectionSignals.EMPTY, searchContext);

        assertSame(searchContext, context.searchContext());
        assertEquals("message", context.currentUserMessage());
        assertEquals("history", context.conversationHistory());
        assertEquals(InterestSelectionSignals.EMPTY, context.interestSelectionSignals());
    }

    @Test
    void compatibilityPromptRequestDefaultsToEmptySearchContext() {
        PromptRequest request = new PromptRequest(
                null, null, null, null, null, null);

        assertEquals(SearchContext.EMPTY, request.searchContext());
    }
}
