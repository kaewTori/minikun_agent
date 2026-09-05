package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.CooperationRouter;
import com.minikun.model.task.TaskModelId;
import com.minikun.model.task.TaskModelProvider;
import com.minikun.model.task.TaskModelRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class TurnPlannerTest {
    @Test
    void casualGreetingUsesTheLeanDirectRoute() {
        TurnPlan plan = planner(null).plan("สวัสดี", "", null, false, null, true);

        assertEquals(TurnPlan.Intent.COMPANION, plan.intent());
        assertEquals(TurnPlan.Execution.DIRECT_STREAM, plan.execution());
        assertFalse(plan.needsMemory());
        assertFalse(plan.needsPersonalKnowledge());
    }

    @Test
    void explicitActionUsesTheToolLoop() {
        TurnPlan plan = planner(null).plan("ช่วยสร้างงานเตือนให้หน่อย", "", null, false, null, true);

        assertEquals(TurnPlan.Intent.ACTION, plan.intent());
        assertEquals(TurnPlan.Execution.TOOL_LOOP, plan.execution());
        assertTrue(plan.needsTools());
    }

    @Test
    void creativeFollowUpCarriesThePreviousTurnRoute() {
        TurnPlan plan = planner(null).plan("ต่อจากตรงนั้นจนจบ",
                "user: ช่วยแต่งเรื่องของริน\nassistant: กาลครั้งหนึ่ง", null, false, null, true);

        assertEquals(TurnPlan.Intent.CREATIVE, plan.intent());
        assertTrue(plan.creative());
    }

    @Test
    void ambiguousFollowUpUsesTheBoundedTaskModelResolution() {
        TaskModelProvider provider = request -> """
                {"intent":"action","needsTools":true,"background":false,"confidence":0.88,"reason":"selected prior action"}
                """;
        @SuppressWarnings("unchecked")
        ObjectProvider<TaskModelRegistry> models = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(models.getIfAvailable())
                .thenReturn(new TaskModelRegistry(Map.of(TaskModelId.OLLAMA, provider)));
        TurnAmbiguityResolver resolver = new TurnAmbiguityResolver(
                models, new ObjectMapper(), true, Duration.ofSeconds(1));

        TurnPlan plan = planner(resolver).plan("เอาอันแรก", "assistant: มีสองทางเลือก", null,
                false, null, true);

        assertEquals(TurnPlan.Intent.ACTION, plan.intent());
        assertTrue(plan.needsTools());
        assertTrue(plan.ambiguous());
        assertEquals(0.88, plan.confidence());
    }

    private TurnPlanner planner(TurnAmbiguityResolver resolver) {
        return new TurnPlanner(new CooperationRouter(), resolver);
    }
}
