package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.investment.InvestmentMonitoringService;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InvestmentMonitorToolTest {
    @Test
    void returnsTheSavedPlanAsReadOnlyEvidence() {
        InvestmentMonitoringService monitoring = mock(InvestmentMonitoringService.class);
        when(monitoring.plan("owner-a")).thenReturn(Map.of("execution", Map.of("live_orders", false)));
        InvestmentMonitorTool tool = new InvestmentMonitorTool(monitoring);

        ToolResult result = tool.execute(new ToolCallContext(new ConversationId("monitor"), "call", "owner-a"),
                Map.of("action", "plan"));

        assertTrue(result.success());
        Map<?, ?> value = (Map<?, ?>) result.value();
        assertTrue(value.get("execution") instanceof Map<?, ?>);
        assertEquals(Boolean.FALSE, ((Map<?, ?>) value.get("execution")).get("live_orders"));
    }
}
