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
