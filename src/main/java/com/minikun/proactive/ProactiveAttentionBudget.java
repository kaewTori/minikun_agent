package com.minikun.proactive;

import com.minikun.notification.NotificationRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A small single-owner attention budget for unsolicited care notifications. */
@Component
public final class ProactiveAttentionBudget {
    private static final Set<String> BUDGETED = Set.of("BRIEFING", "GOAL_REVIEW", "CONVERSATION_THREAD");
    private final Clock clock;
    private final ZoneId zone;
    private final int dailyMaximum;
    private LocalDate day;
    private int used;

    public ProactiveAttentionBudget(
            Clock clock,
            @Value("${minikun.proactive.zone:Asia/Bangkok}") String zone,
            @Value("${minikun.proactive.attention.daily-maximum:3}") int dailyMaximum) {
        this.clock = clock;
        this.zone = ZoneId.of(zone);
        this.dailyMaximum = Math.max(1, dailyMaximum);
    }

    public synchronized boolean tryAcquire(NotificationRequest request) {
        if (!BUDGETED.contains(request.sourceType()) || request.priority() >= 4) return true;
        Instant now = clock.instant();
        LocalDate today = now.atZone(zone).toLocalDate();
        if (!today.equals(day)) {
            day = today;
            used = 0;
        }
        // ponytail: one home-server process needs no persistent quota; use delivery history if restart abuse appears.
        if (used >= dailyMaximum) return false;
        used++;
        return true;
    }
}
