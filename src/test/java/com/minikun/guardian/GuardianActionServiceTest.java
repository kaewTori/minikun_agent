package com.minikun.guardian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.tools.ToolCallContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GuardianActionServiceTest {
    @Test
    void executesOnlyFixedAllowlistedArgvAndAuditsWithoutExposingCommand() {
        List<GuardianActionAudit> audit = new ArrayList<>();
        List<List<String>> invocations = new ArrayList<>();
        GuardianActionService service = new GuardianActionService(
                List.of(new GuardianActionDefinition("restart-ollama", "Restart Ollama",
                        List.of("/fixed/service-manager", "restart", "ollama"))),
                (command, timeout) -> {
                    invocations.add(command);
                    return new GuardianCommandResult(0, false, "token=must-not-leak");
                }, store(audit), Clock.fixed(Instant.parse("2026-08-20T12:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(5));
        ToolCallContext context = new ToolCallContext(new ConversationId("conversation"), "call", "owner");

        service.requested(context, "restart-ollama");
        Map<String, Object> result = service.execute(context, "restart-ollama");

        assertEquals(List.of("/fixed/service-manager", "restart", "ollama"), invocations.getFirst());
        assertTrue((Boolean) result.get("success"));
        assertFalse(result.toString().contains("service-manager"));
        assertFalse(result.toString().contains("must-not-leak"));
        assertEquals(List.of("REQUESTED", "SUCCEEDED"), audit.stream().map(GuardianActionAudit::status).toList());
        assertThrows(IllegalArgumentException.class, () -> service.execute(context, "anything; rm -rf"));
    }

    private GuardianAuditStore store(List<GuardianActionAudit> values) {
        return new GuardianAuditStore() {
            @Override public void save(GuardianActionAudit audit) { values.add(audit); }
            @Override public List<GuardianActionAudit> list(String ownerId, int limit) {
                return values.stream().filter(value -> ownerId.equals(value.ownerId())).limit(limit).toList();
            }
        };
    }
}
