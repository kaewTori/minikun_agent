package com.minikun.agent.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRiskAssessorTest {
    private final AgentRiskAssessor assessor = new AgentRiskAssessor();

    @Test
    void classifiesReadOnlyResearchAsLowRisk() {
        AgentRiskAssessment result = assessor.assess("ค้นหาข้อมูลแล้วสรุปผล", List.of("ค้นหา", "สรุป"));

        assertEquals(AgentRiskLevel.LOW, result.level());
        assertTrue(result.reasons().isEmpty());
    }

    @Test
    void classifiesPersistentChangesAsMediumRisk() {
        AgentRiskAssessment result = assessor.assess("สร้างงานติดตามเรื่องนี้", List.of("บันทึก task"));

        assertEquals(AgentRiskLevel.MEDIUM, result.level());
        assertTrue(result.summary().contains("persistent"));
    }

    @Test
    void classifiesExternalOrDestructiveActionsAsCritical() {
        AgentRiskAssessment result = assessor.assess("ลบข้อมูลแล้วส่งอีเมลแจ้ง", List.of("delete", "email"));

        assertEquals(AgentRiskLevel.CRITICAL, result.level());
        assertTrue(result.level().requiresExplicitReview());
    }
}
