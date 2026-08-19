package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.guardian.GuardianActionService;
import com.minikun.guardian.GuardianAuditStore;
import com.minikun.guardian.GuardianBackupChecker;
import com.minikun.guardian.GuardianCommandResult;
import com.minikun.guardian.GuardianLogReader;
import com.minikun.guardian.HomelabGuardianService;
import com.minikun.systemhealth.SystemHealthReport;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HomelabGuardianRouterTest {
    @Test
    void routesOnlyExplicitHomelabInspectionAndReturnsVerifiedReport() {
        Clock clock = Clock.systemUTC();
        SystemHealthReport report = new SystemHealthReport("UP", true,
                Map.of("status", "UP"), Map.of("status", "UP"), Map.of("status", "UP"),
                Map.of("status", "UP"), Map.of("status", "UP"), Map.of("status", "RUNNING"),
                Map.of("ollama", Map.of("status", "UP", "latency_ms", 1)));
        HomelabGuardianService guardian = new HomelabGuardianService(() -> report,
                new GuardianLogReader(List.of()),
                new GuardianBackupChecker(List.of(), Duration.ofHours(36), clock), clock);
        GuardianAuditStore audit = new GuardianAuditStore() {
            @Override public void save(com.minikun.guardian.GuardianActionAudit value) {}
            @Override public List<com.minikun.guardian.GuardianActionAudit> list(String owner, int limit) {
                return List.of();
            }
        };
        GuardianActionService actions = new GuardianActionService(List.of(),
                (command, timeout) -> new GuardianCommandResult(0, false, ""), audit, clock, Duration.ofSeconds(5));
        HomelabGuardianTool tool = new HomelabGuardianTool(guardian, actions, Optional.empty());
        HomelabGuardianRouter router = new HomelabGuardianRouter(
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(tool))));
        ConversationId conversation = new ConversationId("guardian-route");

        Optional<ToolEvidence> evidence = router.route("ตรวจ homelab ตอนนี้ให้หน่อย", conversation);

        assertTrue(evidence.isPresent());
        assertTrue(evidence.get().success());
        assertTrue(evidence.get().content().contains("ผลตรวจ Homelab ที่ยืนยันจากระบบ: UP"));
        assertFalse(evidence.get().content().contains("GuardianLogSnapshot"));
        assertTrue(router.route("ช่วยตรวจคำตอบนี้ให้หน่อย", conversation).isEmpty());
    }
}
