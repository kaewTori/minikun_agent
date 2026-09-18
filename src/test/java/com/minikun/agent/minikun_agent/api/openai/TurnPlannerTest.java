package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.CooperationRouter;
import com.minikun.model.task.TaskModelId;
import com.minikun.model.task.TaskModelProvider;
import com.minikun.tools.ToolEvidence;
import com.minikun.tools.ToolRequestRouter;
import com.minikun.model.task.TaskModelRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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
        assertEquals("native_tool_loop", plan.routeSource());
    }

    @Test
    void naturalInvestmentReviewUsesTheToolLoop() {
        TurnPlan plan = planner(null).plan("ช่วยทบทวนพอร์ตระยะยาวของฉัน", "", null,
                false, null, true);

        assertEquals(TurnPlan.Intent.ACTION, plan.intent());
        assertEquals(TurnPlan.Execution.TOOL_LOOP, plan.execution());
        assertTrue(plan.needsTools());
    }

    @Test
    void imageOutputIsPartOfTheTurnPlan() {
        TurnPlan plan = planner(null).plan("ช่วยสร้างภาพเมืองลอยฟ้ายามค่ำคืน", "", null, false, null, true);

        assertTrue(plan.imageOutput());
        assertEquals("turn_planner", plan.routeSource());
    }

    @Test
    void voiceModeKeepsVisualWorkOutOfTheTurnPlan() {
        TurnPlan plan = planner(null).plan("ช่วยสร้างภาพเมืองลอยฟ้ายามค่ำคืน", "", null,
                false, null, true, true);

        assertFalse(plan.imageOutput());
    }

    @Test
    void deterministicToolEvidenceIsRecordedAsTheRouteSource() {
        TurnPlan plan = planner(null).plan("อากาศวันนี้เป็นอย่างไร", "", null, false,
                ToolEvidence.verified("weather.get_forecast", "clear"), true);

        assertEquals("deterministic_tool", plan.routeSource());
        assertFalse(plan.needsTools());
    }

    @Test
    void plannerOwnsOrderedDeterministicRouting() {
        AtomicInteger calls = new AtomicInteger();
        ToolRequestRouter router = new ToolRequestRouter() {
            @Override
            public Optional<ToolEvidence> route(String text, com.minikun.agent.minikun_agent.conversation.ConversationId conversationId) {
                calls.incrementAndGet();
                return Optional.of(ToolEvidence.finalVerified("test.route", "done"));
            }
        };
        TurnPlanner planner = new TurnPlanner(new CooperationRouter(), null, null,
                java.util.List.of(router), true);

        Optional<ToolEvidence> result = planner.route("ทำเลย", new com.minikun.agent.minikun_agent.conversation.ConversationId("route"),
                "owner", true);

        assertTrue(result.isPresent());
        assertEquals("test.route", result.get().toolName());
        assertEquals(1, calls.get());
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

    @Test
    void peerPolicyKeepsHighRiskLocalAndRequestsASecondOpinionForAmbiguousExpertWork() {
        TurnPlan medical = planner(null).plan("ยานี้ใช้รักษาอะไร", "", null, false, null, true);
        TurnPlan ambiguousTechnical = planner(null).plan("ต่อ", "assistant: ช่วย debug code", null,
                false, null, true);

        assertFalse(medical.externalPeersAllowed());
        assertFalse(medical.peerMeetingRequired());
        assertTrue(ambiguousTechnical.externalPeersAllowed());
        assertTrue(ambiguousTechnical.peerMeetingRequired());
    }

    private TurnPlanner planner(TurnAmbiguityResolver resolver) {
        return new TurnPlanner(new CooperationRouter(), resolver);
    }
}
