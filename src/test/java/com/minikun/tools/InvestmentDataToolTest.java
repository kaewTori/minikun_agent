package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.investment.InvestmentExternalDataService;
import com.minikun.investment.InvestmentService;
import com.minikun.planner.PlannerConfirmationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class InvestmentDataToolTest {
    @Test
    void convertsTheRevisedBudgetInTheExplicitDirectionAndRejectsAmbiguousOrInvalidAmounts() {
        var external = mock(InvestmentExternalDataService.class);
        var investments = mock(InvestmentService.class);
        var confirmations = mock(PlannerConfirmationService.class);
        var tool = new InvestmentDataTool(investments, external, confirmations);
        var date = java.time.LocalDate.of(2026, 10, 6);
        org.mockito.Mockito.when(external.latestFxRate("THB", "USD")).thenReturn(
                new InvestmentExternalDataService.FxRate("THB", "USD", new java.math.BigDecimal("0.03"), date, "test"));
        org.mockito.Mockito.when(external.latestFxRate("USD", "THB")).thenReturn(
                new InvestmentExternalDataService.FxRate("USD", "THB", new java.math.BigDecimal("33.662"), date, "test"));
        var context = new ToolCallContext(new ConversationId("budget"), "call", "owner-a");
        for (String amount : java.util.List.of("5000", "3800")) {
            ToolResult result = tool.execute(context, Map.of("action", "fx", "base_currency", "THB",
                    "quote_currency", "USD", "amount", amount));
            assertTrue(result.success());
            Map<?, ?> value = (Map<?, ?>) result.value();
            assertEquals(new java.math.BigDecimal(amount).multiply(new java.math.BigDecimal("0.03")).setScale(2),
                    value.get("converted_amount"));
            assertEquals(date, value.get("date"));
            assertEquals("REFERENCE_RATE_BEFORE_BROKER_SPREAD_AND_FEES", value.get("conversion_basis"));
        }
        ToolResult reverse = tool.execute(context, Map.of("action", "fx", "pair", "USD/THB", "amount", "100"));
        assertEquals(new java.math.BigDecimal("3366.20"), ((Map<?, ?>) reverse.value()).get("converted_amount"));
        for (String amount : java.util.List.of("-1", "0", "NaN", "Infinity")) {
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, tool.execute(context,
                    Map.of("action", "fx", "pair", "THB/USD", "amount", amount)).errorCode());
        }
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, tool.execute(context,
                Map.of("action", "fx", "amount", "3800")).errorCode());
        org.mockito.Mockito.when(external.latestFxRate("THB", "USD")).thenThrow(new IllegalStateException("offline"));
        assertEquals(ToolErrorCode.EXECUTION_FAILED, tool.execute(context,
                Map.of("action", "fx", "pair", "THB/USD", "amount", "3800")).errorCode());
        org.mockito.Mockito.verifyNoInteractions(investments, confirmations);
    }

    @Test
    void reportsSetupInsteadOfInventingQuotesWhenApiKeyIsMissing() {
        InvestmentExternalDataService external = new InvestmentExternalDataService(
                RestClient.create(), RestClient.create(), RestClient.create(), RestClient.create(),
                new ObjectMapper(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), "", "MinikunAgent/1.0", "", "");
        InvestmentDataTool tool = new InvestmentDataTool(
                mock(InvestmentService.class), external, mock(PlannerConfirmationService.class));

        ToolResult result = tool.execute(
                new ToolCallContext(new ConversationId("market"), "call"),
                Map.of("action", "quotes", "symbol", "AMZN"));

        assertTrue(result.success());
        assertEquals("not_configured", ((Map<?, ?>) result.value()).get("status"));
    }
}
