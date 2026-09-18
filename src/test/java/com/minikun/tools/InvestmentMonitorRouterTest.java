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

        List.of(
                "ช่วยดูพอร์ตของเรา",
                "มีอะไรอยู่ใน port",
                "ตอนนี้ฉันถืออะไรอยู่",
                "แสดงรายการลงทุนของฉัน",
                "what's in my portfolio",
                "list my positions",
                "show my holdings").forEach(text ->
                        assertTrue(router.route(text, new ConversationId("mixed-" + text.hashCode()), "owner-a")
                                .isPresent(), text));
    }

    @Test
    void ignoresBareMetalScalingQuestions() {
        InvestmentMonitorRouter router = new InvestmentMonitorRouter(mock(ToolExecutor.class), new ObjectMapper());

        assertTrue(router.route(
                "ถ้าเรายังต้องไปเป็น bare metal แต่ว่าเรามีเครื่องอยู่ 4 เครื่องแบบนี้เราก็ทำแผน horizontal scale ก็ได้ถูกไหม",
                new ConversationId("bare-metal"), "owner-a").isEmpty());
    }

    @Test
    void keepsNewsAndMarketQuestionsOnTheirDedicatedRoutes() {
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.success(Map.of("status", "ok")));
        InvestmentMonitorRouter router = new InvestmentMonitorRouter(executor, new ObjectMapper());

        var news = router.route("ข่าวในพอร์ตวันนี้", new ConversationId("news-in-portfolio"), "owner-a");
        assertTrue(news.isPresent());
        assertTrue(!news.get().finalResponse());
        var marketAnalysis = router.route("วิเคราะห์ตลาดหุ้น", new ConversationId("market-analysis"), "owner-a");
        assertTrue(marketAnalysis.isPresent());
        assertTrue(!marketAnalysis.get().finalResponse());
        assertTrue(router.route("วิเคราะห์พอร์ตของเรา", new ConversationId("analysis"), "owner-a").isEmpty());
        assertTrue(router.route("ราคาหุ้นในพอร์ต", new ConversationId("market"), "owner-a").isEmpty());
    }

    @Test
    void refreshesMarketAnalysisSoTheModelReceivesPortfolioAndMarketContext() {
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenReturn(ToolResult.success(Map.of(
                "portfolio", Map.of("symbols", List.of("GIL")),
                "market_snapshot", Map.of("status", "ok"))));
        InvestmentMonitorRouter router = new InvestmentMonitorRouter(executor, new ObjectMapper());

        var evidence = router.route("วิเคราะห์ตลาดหุ้น", new ConversationId("market-context"), "owner-a");

        assertTrue(evidence.isPresent());
        assertTrue(!evidence.get().finalResponse());
        assertTrue(evidence.get().content().contains("GIL"));

        ArgumentCaptor<ToolCall> call = ArgumentCaptor.forClass(ToolCall.class);
        verify(executor).execute(any(), call.capture());
        assertEquals("daily_brief", call.getValue().arguments().get("action"));
        assertEquals(true, call.getValue().arguments().get("refresh"));
    }

    @Test
    void keepsMarketEvidenceWhenTheReportContainsTimestamps() {
        ToolExecutor executor = mock(ToolExecutor.class);
        Instant generatedAt = Instant.parse("2026-09-18T00:00:00Z");
        when(executor.execute(any(), any())).thenReturn(ToolResult.success(Map.of(
                "portfolio", Map.of("symbols", List.of("GIL")),
                "generated_at", generatedAt,
                "market_snapshot", Map.of("as_of", generatedAt))));
        InvestmentMonitorRouter router = new InvestmentMonitorRouter(executor, new ObjectMapper());

        var evidence = router.route("วิเคราะห์ตลาดหุ้น", new ConversationId("market-timestamps"), "owner-a");

        assertTrue(evidence.isPresent());
        assertTrue(evidence.get().success());
        assertTrue(evidence.get().content().contains("GIL"));
    }
}
