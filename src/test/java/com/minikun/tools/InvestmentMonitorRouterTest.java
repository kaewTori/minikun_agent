package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
}
