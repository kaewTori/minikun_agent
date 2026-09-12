package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InvestmentReviewRouterTest {
    @Test
    void routesNaturalPortfolioReviewToReadOnlyReviewAction() {
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any()))
                .thenReturn(ToolResult.success(Map.of("review_type", "long_term_portfolio")));
        InvestmentReviewRouter router = new InvestmentReviewRouter(executor, new ObjectMapper());

        Optional<ToolEvidence> result = router.route(
                "ช่วยทบทวนพอร์ตระยะยาวของฉัน", new ConversationId("review"), "owner-a");

        assertTrue(result.isPresent());
        assertTrue(result.get().success());
        verify(executor).execute(any(), any());
    }

    @Test
    void leavesSingleStockResearchOnTheResearchRoute() {
        InvestmentReviewRouter router = new InvestmentReviewRouter(mock(ToolExecutor.class), new ObjectMapper());

        assertTrue(router.route("ช่วยวิเคราะห์หุ้น AMZN", new ConversationId("research"), "owner-a").isEmpty());
    }

    @Test
    void leavesDailyNewsRequestsToTheInvestmentMonitor() {
        InvestmentReviewRouter router = new InvestmentReviewRouter(mock(ToolExecutor.class), new ObjectMapper());

        assertTrue(router.route("ช่วยสรุปข่าวการลงทุนวันนี้", new ConversationId("news"), "owner-a").isEmpty());
    }
}
