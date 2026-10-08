package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.investment.InvestmentPolicy;
import com.minikun.investment.InvestmentService;
import com.minikun.investment.PortfolioPosition;
import com.minikun.investment.PortfolioSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InvestmentMarketRouterTest {
    @Test
    void serializesQuoteTimestampsInsteadOfReportingAFormatError() {
        InvestmentService investments = mock(InvestmentService.class);
        when(investments.summary("owner-a")).thenReturn(portfolio());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.success(Map.of(
                "status", "ok", "as_of", Instant.parse("2026-10-08T00:00:00Z"), "quotes", List.of())));
        var evidence = new InvestmentMarketRouter(executor, new ObjectMapper(), investments)
                .route("ดูราคาปัจจุบันของ AMZN", new ConversationId("quote-time"), "owner-a").orElseThrow();
        assertTrue(evidence.success(), evidence.content());
        assertTrue(evidence.content().contains("as_of"));
    }

    @Test
    void routesAConcretePriceQuestionWithTheHeldSymbol() {
        InvestmentService investments = mock(InvestmentService.class);
        when(investments.summary("owner-a")).thenReturn(portfolio());
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.success(Map.of("status", "not_configured")));
        InvestmentMarketRouter router = new InvestmentMarketRouter(executor, new ObjectMapper(), investments);

        var evidence = router.route("ดูราคาปัจจุบันของ AMZN", new ConversationId("market"), "owner-a");
        assertTrue(evidence.isPresent());
        assertTrue(evidence.get().finalResponse());
        verify(executor).execute(any(), any());
    }

    @Test
    void leavesFundamentalStockAnalysisOnResearchRoute() {
        InvestmentMarketRouter router = new InvestmentMarketRouter(
                mock(ToolExecutor.class), new ObjectMapper(), mock(InvestmentService.class));

        assertTrue(router.route("ช่วยวิเคราะห์หุ้น AMZN", new ConversationId("research"), "owner-a").isEmpty());
    }

    private PortfolioSummary portfolio() {
        Instant now = Instant.parse("2026-09-11T00:00:00Z");
        return new PortfolioSummary("owner-a", "USD", "AVERAGE_COST", BigDecimal.TEN, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN,
                List.of(new PortfolioPosition("AMZN", "Amazon", "EQUITY", "USD", BigDecimal.ONE,
                        BigDecimal.TEN, BigDecimal.TEN, BigDecimal.valueOf(100), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO)),
                new InvestmentPolicy("owner-a", "USD", "", BigDecimal.valueOf(20), now, now), List.of());
    }
}
