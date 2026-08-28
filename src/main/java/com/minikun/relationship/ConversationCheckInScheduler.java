package com.minikun.relationship;

import com.minikun.notification.NotificationChannel;
import com.minikun.notification.NotificationDispatcher;
import com.minikun.notification.NotificationRequest;
import com.minikun.notification.NotificationSchedulerMonitor;
import com.minikun.proactive.ProactiveNotificationPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Delivers only check-ins for which the user explicitly consented. */
public final class ConversationCheckInScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationCheckInScheduler.class);
    private static final String SCHEDULER = "conversation-check-in";
    private final ConversationThreadService threads;
    private final NotificationDispatcher notifications;
    private final NotificationSchedulerMonitor monitor;
    private final ProactiveNotificationPolicy policy;
    private final Clock clock;

    public ConversationCheckInScheduler(ConversationThreadService threads, NotificationDispatcher notifications,
            NotificationSchedulerMonitor monitor, ProactiveNotificationPolicy policy, Clock clock) {
        this.threads = Objects.requireNonNull(threads);
        this.notifications = Objects.requireNonNull(notifications);
        this.monitor = Objects.requireNonNull(monitor);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
        monitor.register(SCHEDULER);
    }

    @Scheduled(fixedDelayString = "${minikun.relationship.check-in.poll-interval-ms:60000}")
    public void deliverDueCheckIns() {
        Instant now = clock.instant();
        monitor.polled(SCHEDULER, now);
        if (!policy.allows(now)) return;
        for (ConversationThread thread : threads.due(now)) {
            try {
                notifications.publish(new NotificationRequest("CONVERSATION_THREAD", thread.id().toString(),
                        NotificationChannel.REMINDER, "Mini-kun check-in",
                        "พี่สาวครับ เรื่อง “" + thread.topic() + "” เป็นอย่างไรบ้างครับ", 3, "speech_balloon"));
                threads.markCheckedIn(thread, clock.instant());
                monitor.delivered(SCHEDULER, clock.instant());
            } catch (RuntimeException exception) {
                monitor.failed(SCHEDULER, clock.instant());
                LOGGER.warn("process=conversation_check_in event=delivery_failed id={} reason={}",
                        thread.id(), exception.getMessage());
            }
        }
    }
}
