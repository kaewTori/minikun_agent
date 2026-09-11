package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.investment.InvestmentPolicy;
import com.minikun.investment.InvestmentService;
import com.minikun.investment.InvestmentThesisStatus;
import com.minikun.investment.PortfolioPosition;
import com.minikun.investment.PortfolioSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InvestmentAnalyzeToolTest {
    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");

    @Test
    void reviewReturnsPortfolioThesesAndKnowledgeInputs() {
        InvestmentService service = mock(InvestmentService.class);
        PortfolioSummary portfolio = new PortfolioSummary(
                "owner-a", "USD", "AVERAGE_COST", BigDecimal.valueOf(100), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(100),
                List.of(new PortfolioPosition("AMZN", "Amazon", "EQUITY", "USD", BigDecimal.ONE,
                        BigDecimal.valueOf(100), BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)),
                new InvestmentPolicy("owner-a", "USD", "VTI", BigDecimal.valueOf(20), NOW, NOW), List.of());
        when(service.summary("owner-a")).thenReturn(portfolio);
        when(service.theses("owner-a", InvestmentThesisStatus.ACTIVE)).thenReturn(List.of());

        ToolResult result = new InvestmentAnalyzeTool(service).execute(
                new ToolCallContext(new ConversationId("review"), "call", "owner-a"),
                Map.of("action", "review"));

        assertTrue(result.success());
        Map<?, ?> review = (Map<?, ?>) result.value();
        assertEquals("long_term_portfolio", review.get("review_type"));
        assertEquals(1, ((List<?>) review.get("acquired_knowledge")).size());
        Map<?, ?> knowledge = (Map<?, ?>) ((List<?>) review.get("acquired_knowledge")).getFirst();
        assertEquals("unavailable", knowledge.get("status"));
    }
}
