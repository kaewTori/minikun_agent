package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.minikun.search.model.ExternalContextAction;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import org.junit.jupiter.api.Test;

class ExternalContextPlannerTest {
    private final ExternalContextPlanner planner = new ExternalContextPlanner();

    @Test
    void combinesSearchAndExplicitUrlIntoSearchThenOpen() {
        var decision = planner.plan(
                new SearchDecision(true, "latest java", SearchDecisionReason.CURRENT_INFORMATION),
                true, true, false);

        assertEquals(ExternalContextAction.SEARCH_THEN_OPEN, decision.action());
    }

    @Test
    void keepsExplicitUrlReadableWithoutSearch() {
        var decision = planner.plan(
                new SearchDecision(false, "read this", SearchDecisionReason.GENERAL_KNOWLEDGE),
                true, false, false);

        assertEquals(ExternalContextAction.OPEN_EXPLICIT_URL, decision.action());
    }
}
