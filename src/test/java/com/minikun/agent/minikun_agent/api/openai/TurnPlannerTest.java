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
    void slideDeliverableUsesTheToolLoopButSlidePlanningDoesNotCreateADeck() {
        TurnPlanner planner = planner(null);
        TurnPlan deck = planner.plan("ช่วยทำสไลด์แนะนำ homelab 8 หน้า", "", null, false, null, true);
        TurnPlan revision = planner.plan("ช่วยย่อข้อความในสไลด์หน้า 3", "", null, false, null, true);
        TurnPlan plan = planner.plan("ทำแผนให้มินิคุงสามารถทำสไลด์ออกมาให้เรา", "", null,
                false, null, true);
        TurnPlan howTo = planner.plan("วิธีแก้ข้อความใน PowerPoint", "", null, false, null, true);

        assertEquals(TurnPlan.Execution.TOOL_LOOP, deck.execution());
        assertTrue(deck.needsTools());
        assertEquals(TurnPlan.Execution.TOOL_LOOP, revision.execution());
        assertTrue(revision.needsTools());
        assertEquals(TurnPlan.Execution.DIRECT_STREAM, plan.execution());
        assertFalse(plan.needsTools());
        assertEquals(TurnPlan.Execution.DIRECT_STREAM, howTo.execution());
        assertFalse(howTo.needsTools());
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
    void storytellingNeedsAnExplicitImageRequestWhenAutoIllustrationIsOff() {
        TurnPlanner planner = new TurnPlanner(new CooperationRouter(),
                (TurnAmbiguityResolver) null, null, false);

        assertFalse(planner.plan("เล่าเรื่องสงครามโลกครั้งที่สองให้ฟัง", "", null,
                false, null, true).imageOutput());
        assertFalse(planner.plan("แต่งเรื่องสั้นเกี่ยวกับหุ่นยนต์ที่กลัวฝน", "", null,
                false, null, true).imageOutput());
        assertTrue(planner.plan("เล่านิทานเรื่องแมวพร้อมภาพประกอบ", "", null,
                false, null, true).imageOutput());
        assertTrue(planner.plan("ช่วยทำอินโฟกราฟิกเรื่องประหยัดไฟ", "", null,
                false, null, true).imageOutput());
    }

    @Test
    void graphicApprovalUsesTheLatestVisualRequestAndItsApprovedOutline() {
        TurnPlanner planner = new TurnPlanner(new CooperationRouter(), (TurnAmbiguityResolver) null, null, false);
        String original = "อยากได้วิธีคิดของมินิคุง ในรูปแบบ info";
        var history = java.util.List.of(
                new com.minikun.agent.minikun_agent.conversation.ChatMessage("user", original),
                new com.minikun.agent.minikun_agent.conversation.ChatMessage("assistant", "ทำความเข้าใจ ตรวจสอบ ตอบสนอง"));
        String resolved = planner.resolveVisualRequest("เอาตามนี้เลย", history);
        assertTrue(resolved.contains(original));
        assertTrue(resolved.contains("ทำความเข้าใจ ตรวจสอบ ตอบสนอง"));
        assertTrue(planner.plan("เอาตามนี้เลย", "", null, false, null, false, false, resolved).imageOutput());
        assertFalse(planner.plan("เอาตามนี้เลย", "", null, false, null, false, true, resolved).imageOutput());
        assertEquals("ต่อ", planner.resolveVisualRequest("ต่อ", history));
        assertEquals("ไม่ต้องสร้างภาพ", planner.resolveVisualRequest("ไม่ต้องสร้างภาพ", history));
        var unrelated = new java.util.ArrayList<>(history);
        unrelated.add(new com.minikun.agent.minikun_agent.conversation.ChatMessage("user", "เล่าเรื่องให้ฟัง"));
        unrelated.add(new com.minikun.agent.minikun_agent.conversation.ChatMessage("assistant", "กาลครั้งหนึ่ง"));
        assertEquals("เอาตามนี้เลย", planner.resolveVisualRequest("เอาตามนี้เลย", unrelated));
    }

    @Test
    void voiceModeKeepsVisualWorkOutOfTheTurnPlan() {
        TurnPlan plan = planner(null).plan("ช่วยสร้างภาพเมืองลอยฟ้ายามค่ำคืน", "", null,
                false, null, true, true);

        assertFalse(plan.imageOutput());
    }

    @Test
    void graphicRevisionCarriesTheOriginalTopicAndOutlineAcrossApprovalsAndEarlierRevisions() {
        TurnPlanner planner = new TurnPlanner(new CooperationRouter(), (TurnAmbiguityResolver) null, null, false);
        String original = "อยากได้วิธีคิดของมินิคุง ในรูปแบบ info";
        String outline = "ทำความเข้าใจ ตรวจสอบ ให้เหตุผล ตอบสนอง";
        var history = new java.util.ArrayList<>(java.util.List.of(
                new com.minikun.agent.minikun_agent.conversation.ChatMessage("user", original),
                new com.minikun.agent.minikun_agent.conversation.ChatMessage("assistant", outline),
                new com.minikun.agent.minikun_agent.conversation.ChatMessage("user", "เอาตามนี้เลย"),
                new com.minikun.agent.minikun_agent.conversation.ChatMessage("assistant", "สร้างไฟล์ SVG และแนบให้แล้วครับ")));
        String message = "ปรับให้ลองทำเป็น flowchart";
        String resolved = planner.resolveVisualRequest(message, history);
        assertEquals(message, com.minikun.visual.StoryIllustrationIntentDetector.currentRequest(resolved));
        assertTrue(resolved.contains(original));
        assertTrue(resolved.contains(outline));
        history.add(new com.minikun.agent.minikun_agent.conversation.ChatMessage("user", message));
        history.add(new com.minikun.agent.minikun_agent.conversation.ChatMessage("assistant", "แนบ flowchart แล้ว"));
        String back = planner.resolveVisualRequest("เปลี่ยนเป็น infographic", history);
        assertEquals("เปลี่ยนเป็น infographic", com.minikun.visual.StoryIllustrationIntentDetector.currentRequest(back));
        assertTrue(back.contains(original));
        assertTrue(back.contains(outline));
        history.add(new com.minikun.agent.minikun_agent.conversation.ChatMessage("user", "วันนี้อากาศเป็นยังไง"));
        history.add(new com.minikun.agent.minikun_agent.conversation.ChatMessage("assistant", "ฝนตกครับ"));
        assertEquals(message, planner.resolveVisualRequest(message, history));
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
