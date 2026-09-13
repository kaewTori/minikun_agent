package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.investment.InvestmentPolicy;
import com.minikun.investment.PortfolioPosition;
import com.minikun.investment.PortfolioSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InvestmentMonitorRouterTest {
    @Test
    void routesDailyInvestmentNewsToTheMonitorTool() {
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.success(Map.of("status", "ok")));
        InvestmentMonitorRouter router = new InvestmentMonitorRouter(executor, new ObjectMapper());

        var evidence = router.route("สรุปข่าวการลงทุนวันนี้", new ConversationId("news"), "owner-a");

        assertTrue(evidence.isPresent());
        assertTrue(evidence.get().success());
        verify(executor).execute(any(), any());
    }

    @Test
    void leavesPureQuoteRequestsToTheMarketRouter() {
        InvestmentMonitorRouter router = new InvestmentMonitorRouter(mock(ToolExecutor.class), new ObjectMapper());

        assertTrue(router.route("ดูราคาปัจจุบันของ AMZN", new ConversationId("price"), "owner-a").isEmpty());
    }

    @Test
    void loadsTheSavedPlanForAPlainPortfolioInventoryQuestion() {
        ToolExecutor executor = mock(ToolExecutor.class);
        Instant now = Instant.now();
        PortfolioSummary portfolio = new PortfolioSummary("owner-a", "THB", "AVERAGE_COST", new BigDecimal("100"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(new PortfolioPosition("GIL", "", "EQUITY", "THB", new BigDecimal("2"),
                        new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("100"),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)),
                new InvestmentPolicy("owner-a", "THB", "", new BigDecimal("20"), now, now), List.of());
        when(executor.execute(any(), any())).thenReturn(ToolResult.success(Map.of("portfolio", portfolio)));
        InvestmentMonitorRouter router = new InvestmentMonitorRouter(executor, new ObjectMapper());

        var evidence = router.route("พอร์ตเรามีอะไรบ้าง", new ConversationId("inventory"), "owner-a");

        assertTrue(evidence.isPresent());
        assertTrue(evidence.get().finalResponse());
        assertTrue(evidence.get().content().contains("GIL"));

        ArgumentCaptor<ToolCallContext> context = ArgumentCaptor.forClass(ToolCallContext.class);
        ArgumentCaptor<ToolCall> call = ArgumentCaptor.forClass(ToolCall.class);
        verify(executor).execute(context.capture(), call.capture());
        assertEquals("owner-a", context.getValue().ownerId());
        assertEquals("plan", call.getValue().arguments().get("action"));
    }

    @Test
    void recognizesMixedThaiAndEnglishPortfolioWording() {
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.success(Map.of("portfolio", "verified")));
        InvestmentMonitorRouter router = new InvestmentMonitorRouter(executor, new ObjectMapper());

        assertTrue(router.route("port เรามีหุ้นอะไรบ้าง", new ConversationId("mixed"), "owner-a").isPresent());
    }
}
