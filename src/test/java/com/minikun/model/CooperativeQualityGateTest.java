package com.minikun.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;

class CooperativeQualityGateTest {
    private final CooperativeQualityGate gate = new CooperativeQualityGate(true, 0.85, 0.08);
    private final Prompt sizingPrompt = new Prompt(
            "เรามี micro service ที่เป็น JDK25 Spring Boot 4.1 start แบบ bare metal "
                    + "บนเครื่อง RAM 32 GB และต้อง start ทั้งหมด 130 apps ต้อง config Java ยังไง");

    @Test
    void acceptsAnswerThatPreservesFactsAndFitsMemoryBudget() {
        String revised = """
                สำหรับ JDK 25 และ Spring Boot 4.1 แบบ bare metal บน RAM 32 GB จำนวน 130 apps
                ควรเริ่มแบบ conservative เช่น -Xmx96m -XX:MaxMetaspaceSize=32m
                -XX:MaxDirectMemorySize=16m -XX:ReservedCodeCacheSize=16m
                known caps คือ 130 × 160 MB = 20.31 GB และต้องวัด native/thread memory จริงอีกครั้ง
                """;

        CooperativeQualityGate.Decision decision = gate.evaluate(sizingPrompt, "draft", revised);

        assertTrue(decision.accepted(), decision.summary());
    }

    @Test
    void rejectsReviewerThatChangesJdkVersion() {
        String revised = """
                ใช้ Java 21 กับ Spring Boot 4.1 แบบ bare metal บน RAM 32 GB สำหรับ 130 apps
                และตั้ง -Xmx96m
                """;

        CooperativeQualityGate.Decision decision = gate.evaluate(sizingPrompt, "draft", revised);

        assertFalse(decision.accepted());
        assertTrue(decision.summary().contains("missing_or_changed_version:java=25"));
    }

    @Test
    void rejectsJvmCapsThatExceedFleetMemory() {
        String revised = """
                สำหรับ JDK 25 และ Spring Boot 4.1 แบบ bare metal บน RAM 32 GB จำนวน 130 apps
                ใช้ -Xmx256m -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=128m
                -XX:ReservedCodeCacheSize=64m
                """;

        CooperativeQualityGate.Decision decision = gate.evaluate(sizingPrompt, "draft", revised);

        assertFalse(decision.accepted());
        assertTrue(decision.summary().contains("jvm_memory_budget_exceeded"));
    }

    @Test
    void rejectsInconsistentMemoryEquation() {
        String revised = """
                สำหรับ JDK 25 และ Spring Boot 4.1 แบบ bare metal บน RAM 32 GB จำนวน 130 apps
                หากใช้ 200 MB ต่อ app จะเป็น 130 × 200 MB = 13 GB
                """;

        CooperativeQualityGate.Decision decision = gate.evaluate(sizingPrompt, "draft", revised);

        assertFalse(decision.accepted());
        assertTrue(decision.summary().contains("inconsistent_memory_equation"));
    }

    @Test
    void rejectsAnswerThatDropsOriginalCapacityConstraints() {
        CooperativeQualityGate.Decision decision = gate.evaluate(
                sizingPrompt, "draft", "สำหรับ JDK 25 และ Spring Boot 4.1 ควรทดสอบโหลดก่อนใช้งานจริง");

        assertFalse(decision.accepted());
        assertTrue(decision.summary().contains("missing_workload_count:130"));
        assertTrue(decision.summary().contains("missing_total_memory:32768MiB"));
        assertTrue(decision.summary().contains("missing_deployment_constraint:bare_metal"));
    }

    @Test
    void createsSafeFleetBoundForRejectedCapacityAnswer() {
        String fallback = gate.safeFallback(sizingPrompt);

        assertTrue(fallback.contains("214.25 MiB"));
        assertTrue(fallback.contains("heap, metaspace"));
        assertTrue(gate.hasDeterministicConstraints(sizingPrompt));
    }

    @Test
    void usesAGenericFallbackForAnUnconstrainedTechnicalQuestion() {
        String fallback = gate.safeFallback(new Prompt(
                "What does the Spring AI reference say about Redis chat memory?"));

        assertTrue(fallback.contains("consistency checks"));
        assertFalse(fallback.toLowerCase().contains("jvm"));
    }

    @Test
    void rejectsMutuallyExclusiveGarbageCollectors() {
        String revised = """
                สำหรับ JDK25 Spring Boot 4.1 แบบ bare metal บน RAM 32 GB จำนวน 130 apps
                ใช้ -Xmx96m -XX:+UseG1GC -XX:+UseZGC
                """;

        CooperativeQualityGate.Decision decision = gate.evaluate(sizingPrompt, "draft", revised);

        assertFalse(decision.accepted());
        assertTrue(decision.summary().contains("conflicting_garbage_collectors"));
    }

    @Test
    void rejectsDisclosureOfInternalReviewProcess() {
        String revised = """
                มินิคุงทบทวนคำตอบร่างจาก Ollama แล้ว สำหรับ JDK25 Spring Boot 4.1 แบบ bare metal
                บน RAM 32 GB จำนวน 130 apps ควรใช้ total process budget 160 MB ต่อ app
                """;

        CooperativeQualityGate.Decision decision = gate.evaluate(sizingPrompt, "draft", revised);

        assertFalse(decision.accepted());
        assertTrue(decision.summary().contains("internal_process_disclosure"));
    }
}
