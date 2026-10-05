package com.minikun.guardian;

import com.minikun.notification.NotificationChannel;
import com.minikun.notification.NotificationDispatcher;
import com.minikun.notification.NotificationRequest;
import com.minikun.notification.NotificationSchedulerMonitor;
import com.minikun.proactive.ProactiveNotificationPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Proactively alerts on stable incidents and sends one recovery notification. */
@Component
@ConditionalOnProperty(name = "minikun.guardian.monitor.enabled", havingValue = "true", matchIfMissing = true)
public final class HomelabGuardianScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(HomelabGuardianScheduler.class);
    private static final String SCHEDULER = "homelab-guardian";
    private final HomelabGuardianService guardian;
    private final NotificationDispatcher notifications;
    private final NotificationSchedulerMonitor monitor;
    private final ProactiveNotificationPolicy policy;
    private final GuardianAlertStateStore stateStore;
    private final Clock clock;
    private final int failureThreshold;
    private final Duration repeatCooldown;

    public HomelabGuardianScheduler(
            HomelabGuardianService guardian,
            NotificationDispatcher notifications,
            NotificationSchedulerMonitor monitor,
            ProactiveNotificationPolicy policy,
            GuardianAlertStateStore stateStore,
            Clock clock,
            @Value("${minikun.guardian.monitor.failure-threshold:2}") int failureThreshold,
            @Value("${minikun.guardian.monitor.repeat-cooldown:6h}") Duration repeatCooldown) {
        this.guardian = Objects.requireNonNull(guardian, "guardian service must not be null");
        this.notifications = Objects.requireNonNull(notifications, "notification dispatcher must not be null");
        this.monitor = Objects.requireNonNull(monitor, "notification monitor must not be null");
        this.policy = Objects.requireNonNull(policy, "proactive policy must not be null");
        this.stateStore = Objects.requireNonNull(stateStore, "guardian state store must not be null");
        this.clock = Objects.requireNonNull(clock, "guardian scheduler clock must not be null");
        if (failureThreshold < 1) throw new IllegalArgumentException("guardian failure threshold must be positive");
        if (repeatCooldown == null || repeatCooldown.isNegative() || repeatCooldown.isZero()) {
            throw new IllegalArgumentException("guardian repeat cooldown must be positive");
        }
        this.failureThreshold = failureThreshold;
        this.repeatCooldown = repeatCooldown;
        this.monitor.register(SCHEDULER);
    }

    @Scheduled(fixedDelayString = "${minikun.guardian.monitor.poll-interval-ms:60000}")
    public void inspectAndNotify() {
        Instant now = clock.instant();
        monitor.polled(SCHEDULER, now);
        try {
            GuardianReport report = guardian.inspect();
            GuardianAlertState previous = stateStore.load();
            if (report.healthy()) {
                healthy(previous, now);
            } else {
                unhealthy(report, previous, now);
            }
        } catch (RuntimeException exception) {
            monitor.failed(SCHEDULER, clock.instant());
            LOGGER.warn("process=homelab_guardian event=inspection_failed reason={}", exception.getMessage());
        }
    }

    private void healthy(GuardianAlertState previous, Instant now) {
        boolean recoveryRequired = !"UP".equals(previous.status()) && !previous.notifiedFingerprint().isBlank();
        boolean canNotify = recoveryRequired && policy.allows(now);
        if (canNotify) {
            notifications.publish(new NotificationRequest("GUARDIAN", "recovery:" + previous.notifiedFingerprint(),
                    NotificationChannel.REMINDER, "Mini-kun homelab recovered",
                    "พี่สาวครับ ระบบ homelab ที่มินิคุงเฝ้าดูกลับมาอยู่ในสถานะปกติแล้วครับ",
                    3, "white_check_mark,computer"));
            monitor.delivered(SCHEDULER, clock.instant());
        }
        if (recoveryRequired && !canNotify) {
            stateStore.save(new GuardianAlertState("", previous.notifiedFingerprint(),
                    "RECOVERED_PENDING", 0, previous.lastNotifiedAt()));
            return;
        }
        stateStore.save(new GuardianAlertState("", "", "UP", 0,
                canNotify ? now : previous.lastNotifiedAt()));
    }

    private void unhealthy(GuardianReport report, GuardianAlertState previous, Instant now) {
        String fingerprint = fingerprint(report);
        int consecutive = fingerprint.equals(previous.observedFingerprint())
                ? previous.consecutiveIssues() + 1 : 1;
        boolean changed = !fingerprint.equals(previous.notifiedFingerprint());
        boolean cooldownElapsed = previous.lastNotifiedAt() == null
                || !now.isBefore(previous.lastNotifiedAt().plus(repeatCooldown));
        boolean notify = consecutive >= failureThreshold && policy.allows(now) && (changed || cooldownElapsed);
        String notifiedFingerprint = previous.notifiedFingerprint();
        Instant lastNotifiedAt = previous.lastNotifiedAt();
        if (notify) {
            notifications.publish(new NotificationRequest("GUARDIAN", fingerprint,
                    NotificationChannel.REMINDER, "Mini-kun homelab " + report.status(),
                    message(report), report.status().equals("CRITICAL") ? 5 : 4,
                    "warning,computer"));
            notifiedFingerprint = fingerprint;
            lastNotifiedAt = now;
            monitor.delivered(SCHEDULER, clock.instant());
        }
        stateStore.save(new GuardianAlertState(fingerprint, notifiedFingerprint, report.status(), consecutive,
                lastNotifiedAt));
    }

    private String message(GuardianReport report) {
        StringBuilder result = new StringBuilder("พี่สาวครับ มินิคุงพบสถานะ ")
                .append(report.status()).append(" ใน homelab");
        int shown = 0;
        for (GuardianFinding finding : report.findings()) {
            if (shown >= 5) break;
            String block = new StringBuilder("\n• ")
                .append(finding.component()).append(": ").append(finding.summary())
                .append("\n  ").append(finding.causeConfidence().equals("CONFIRMED")
                        ? "สาเหตุของคำเตือนที่ยืนยันได้: " : "ผลสืบเบื้องต้น (ยังไม่ยืนยันต้นเหตุ): ")
                .append(finding.cause())
                .append("\n  หลักฐาน: ").append(finding.evidence())
                .append("\n  แนะนำ: ").append(finding.recommendedAction()).toString();
            if (shown > 0 && (result.toString() + block).getBytes(StandardCharsets.UTF_8).length > 3300) break;
            result.append(bounded(block, 3000));
            shown++;
        }
        if (report.findings().size() > shown) {
            result.append("\nยังมีอีก ").append(report.findings().size() - shown)
                    .append(" รายการ ถาม ‘ตรวจ homelab ให้หน่อย’ เพื่อดูทั้งหมดครับ");
        }
        result.append("\nตรวจจาก health probe, log และ backup ที่ตั้งค่าไว้ ณ ").append(report.generatedAt());
        return result.toString();
    }

    private String bounded(String value, int maxBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return value;
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int point = value.codePointAt(offset);
            String character = new String(Character.toChars(point));
            bytes += character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > maxBytes - 3) break;
            result.append(character);
            offset += Character.charCount(point);
        }
        return result.append('…').toString();
    }

    private String fingerprint(GuardianReport report) {
        String value = report.findings().stream()
                .map(finding -> finding.severity() + ":" + finding.code() + ":" + finding.component())
                .sorted().collect(java.util.stream.Collectors.joining("|"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
