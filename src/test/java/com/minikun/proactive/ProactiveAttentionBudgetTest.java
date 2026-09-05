package com.minikun.proactive;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.notification.NotificationChannel;
import com.minikun.notification.NotificationRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ProactiveAttentionBudgetTest {
    private final ProactiveAttentionBudget budget = new ProactiveAttentionBudget(
            Clock.fixed(Instant.parse("2026-09-05T03:00:00Z"), ZoneOffset.UTC), "Asia/Bangkok", 2);

    @Test
    void limitsUnsolicitedCareButNotExplicitRemindersOrUrgentAlerts() {
        assertTrue(budget.tryAcquire(request("BRIEFING", 3)));
        assertTrue(budget.tryAcquire(request("CONVERSATION_THREAD", 3)));
        assertFalse(budget.tryAcquire(request("GOAL_REVIEW", 3)));
        assertTrue(budget.tryAcquire(request("PLANNER", 3)));
        assertTrue(budget.tryAcquire(request("GUARDIAN", 4)));
    }

    private NotificationRequest request(String source, int priority) {
        return new NotificationRequest(source, source + "-1", NotificationChannel.REMINDER,
                "Mini-kun", "message", priority, "");
    }
}
