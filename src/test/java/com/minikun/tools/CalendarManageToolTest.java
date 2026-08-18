package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.planner.PendingPlannerConfirmation;
import com.minikun.planner.PlannerConfirmationService;
import com.minikun.planner.PlannerConfirmationStore;
import com.minikun.planner.PlannerEvent;
import com.minikun.planner.PlannerService;
import com.minikun.planner.PlannerStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalendarManageToolTest {
    @Test
    void delegatesAgendaReadsToTheExistingPlannerService() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC);
        PlannerService planner = new PlannerService(new EmptyPlannerStore(), clock);
        PlannerManageTool plannerTool = new PlannerManageTool(
                planner, new PlannerConfirmationService(new EmptyConfirmationStore(), clock));
        CalendarManageTool calendar = new CalendarManageTool(plannerTool);

        ToolResult result = calendar.execute(
                new ToolCallContext(new ConversationId("calendar"), "call"),
                Map.of("action", "list"));

        assertTrue(result.success());
        assertEquals("list", ((Map<?, ?>) result.value()).get("action"));
        assertEquals(List.of(), ((Map<?, ?>) result.value()).get("events"));
        assertEquals("calendar.manage", calendar.definition().name());
    }

    private static final class EmptyPlannerStore implements PlannerStore {
        @Override
        public PlannerEvent create(PlannerEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PlannerEvent> find(UUID id, String conversationId) {
            return Optional.empty();
        }

        @Override
        public List<PlannerEvent> list(String conversationId) {
            return List.of();
        }

        @Override
        public List<PlannerEvent> findDue(Instant now) {
            return List.of();
        }

        @Override
        public PlannerEvent update(PlannerEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean cancel(UUID id, String conversationId, Instant updatedAt) {
            return false;
        }

        @Override
        public void markDelivered(PlannerEvent event, Instant now) {
        }
    }

    private static final class EmptyConfirmationStore implements PlannerConfirmationStore {
        @Override
        public void save(PendingPlannerConfirmation confirmation) {
        }

        @Override
        public Optional<PendingPlannerConfirmation> find(String conversationId, Instant now) {
            return Optional.empty();
        }

        @Override
        public void clear(String conversationId) {
        }
    }
}
