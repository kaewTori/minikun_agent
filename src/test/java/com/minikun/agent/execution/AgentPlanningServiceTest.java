package com.minikun.agent.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;

class AgentPlanningServiceTest {
    private final AgentPlanningService planning = new AgentPlanningService(true, 8, 2000);

    @Test
    void doesNotCreatePlanForSimpleConversation() {
        assertTrue(planning.plan(new Prompt("วันนี้อากาศเป็นยังไง")).isEmpty());
    }

    @Test
    void buildsOrderedPlanFromThaiSequence() {
        AgentPlanDraft plan = planning.plan(new Prompt(
                "ตรวจสอบ reminder จากนั้นแก้รายการที่เสีย แล้วค่อยสรุปผลให้เรา"))
                .orElseThrow();

        assertEquals(3, plan.steps().size());
        assertTrue(plan.steps().get(0).contains("ตรวจสอบ reminder"));
        assertTrue(plan.steps().get(1).contains("แก้รายการที่เสีย"));
        assertTrue(plan.steps().get(2).contains("สรุปผล"));
    }

    @Test
    void preservesInlineNumberedSteps() {
        AgentPlanDraft plan = planning.plan(new Prompt(
                "ช่วยทำตามแผนนี้: 1) พิจารณาข้อดีของชา 2) เปรียบเทียบกับกาแฟ 3) สรุปเป็นสองข้อ"))
                .orElseThrow();

        assertEquals(List.of("พิจารณาข้อดีของชา", "เปรียบเทียบกับกาแฟ", "สรุปเป็นสองข้อ"), plan.steps());
    }

    @Test
    void enrichesPromptWithoutReplacingOriginalUserMessage() {
        AgentPlanDraft draft = planning.plan(new Prompt(
                "ค้นหาข้อมูล จากนั้นวิเคราะห์ แล้วค่อยสรุปผล"))
                .orElseThrow();
        AgentRun run = new AgentExecutionService(
                new InMemoryAgentExecutionStore(), new com.fasterxml.jackson.databind.ObjectMapper(),
                java.time.Clock.systemUTC(), 1, 12)
                .start("owner", "conversation", draft).orElseThrow();

        Prompt enriched = planning.enrich(new Prompt(draft.objective()), run);

        assertEquals(draft.objective(), enriched.getInstructions().get(0).getText());
        assertTrue(enriched.getInstructions().stream().anyMatch(SystemMessage.class::isInstance));
        String internalPlan = enriched.getInstructions().stream()
                .filter(SystemMessage.class::isInstance)
                .map(message -> message.getText())
                .findFirst().orElseThrow();
        assertTrue(internalPlan.contains(run.id().toString()));
        assertTrue(internalPlan.contains("claim an action succeeded without a successful tool result"));
    }

    @Test
    void marksHighImpactPlanForExplicitReview() {
        AgentPlanDraft draft = planning.plan(new Prompt(
                "ตรวจสอบรายการ แล้วลบไฟล์ จากนั้นสรุปผล")).orElseThrow();

        assertEquals(AgentRiskLevel.HIGH, draft.riskAssessment().level());
        assertTrue(draft.riskAssessment().level().requiresExplicitReview());
    }

    @Test
    void includesActiveGoalsWithoutTreatingThemAsInstructions() {
        AgentPlanDraft draft = planning.plan(new Prompt("ค้นหาข้อมูล จากนั้นวิเคราะห์ แล้วค่อยสรุปผล")).orElseThrow();
        AgentRun run = new AgentExecutionService(
                new InMemoryAgentExecutionStore(), new com.fasterxml.jackson.databind.ObjectMapper(),
                java.time.Clock.systemUTC(), 1, 12).start("owner", "conversation", draft).orElseThrow();

        Prompt enriched = planning.enrich(new Prompt(draft.objective()), run,
                "- สุขภาพ: 20% (current 2.0 / target 10.0 กิโล)");

        String internalPlan = enriched.getInstructions().get(1).getText();
        assertTrue(internalPlan.contains("active_goals:"));
        assertTrue(internalPlan.contains("do not claim progress"));
    }
}
