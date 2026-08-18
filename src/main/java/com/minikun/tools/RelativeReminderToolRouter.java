package com.minikun.tools;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.minikun.agent.minikun_agent.conversation.ConversationId;

/**
 * Resolves unambiguous relative reminder requests without asking the model to
 * perform date arithmetic.
 */
@Component
@ConditionalOnProperty(name = "minikun.planner.enabled", havingValue = "true", matchIfMissing = true)
public final class RelativeReminderToolRouter implements ToolRequestRouter {
    private static final String TOOL_NAME = "planner.manage";
    private static final Pattern RELATIVE_TIME = Pattern.compile(
            "(?iu)(?:(?:ใน|ภายใน)\\s*)?อีก\\s*([0-9๐-๙]+)\\s*"
                    + "(วินาที|นาที|ชั่วโมง|ชม\\.?|seconds?|minutes?|hours?)"
                    + "|(?:in|after)\\s+([0-9]+)\\s*(seconds?|minutes?|hours?)");
    private static final int MAX_RELATIVE_MINUTES = 7 * 24 * 60;

    private final ToolExecutor executor;
    private final Clock clock;
    private final String defaultTimezone;

    public RelativeReminderToolRouter(
            ToolExecutor executor,
            Clock clock,
            @Value("${minikun.time.default-zone:Asia/Bangkok}") String defaultTimezone) {
        this.executor = Objects.requireNonNull(executor, "tool executor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.defaultTimezone = Objects.requireNonNullElse(defaultTimezone, "Asia/Bangkok");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        if (userText == null || userText.isBlank() || conversationId == null || !isReminderRequest(userText)) {
            return Optional.empty();
        }

        Matcher matcher = RELATIVE_TIME.matcher(userText);
        if (!matcher.find()) {
            return Optional.empty();
        }

        RelativeDelay delay;
        try {
            delay = delay(matcher);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        String title = title(userText, matcher);
        ZoneId zone;
        try {
            zone = ZoneId.of(defaultTimezone);
        } catch (Exception exception) {
            return Optional.of(ToolEvidence.failed(TOOL_NAME,
                    "ขออภัยครับ timezone ของระบบไม่ถูกต้อง: " + defaultTimezone));
        }

        ZonedDateTime startsAt = ZonedDateTime.now(clock.withZone(zone)).plus(delay.duration());
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("action", "create");
        arguments.put("title", title);
        arguments.put("note", userText.trim());
        arguments.put("at", startsAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        arguments.put("timezone", zone.getId());
        arguments.put("remind_before_minutes", 0);
        arguments.put("recurrence", "NONE");

        String callId = "planner-relative-route-" + UUID.randomUUID();
        ToolResult result = executor.execute(
                new ToolCallContext(conversationId, callId),
                new ToolCall(callId, TOOL_NAME, arguments));
        if (!result.success()) {
            return Optional.of(ToolEvidence.failed(TOOL_NAME,
                    "ขออภัยครับ ตั้งการแจ้งเตือนไม่สำเร็จ: " + result.error()));
        }
        return Optional.of(ToolEvidence.pendingConfirmation(
                TOOL_NAME, formatProposal(title, startsAt, result.value())));
    }

    private boolean isReminderRequest(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("แจ้งเตือน")
                || normalized.contains("เตือน")
                || normalized.contains("ปลุก")
                || normalized.contains("remind")
                || normalized.contains("notify")
                || normalized.contains("alarm");
    }

    private RelativeDelay delay(Matcher matcher) {
        String amountText = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
        String unit = matcher.group(2) != null ? matcher.group(2).toLowerCase(Locale.ROOT) : matcher.group(4);
        long amount = Long.parseLong(normalizeDigits(amountText));
        if (amount <= 0) {
            throw new IllegalArgumentException("relative reminder duration must be positive");
        }
        Duration duration = switch (unit) {
            case "วินาที", "second", "seconds" -> Duration.ofSeconds(amount);
            case "นาที", "minute", "minutes" -> Duration.ofMinutes(amount);
            case "ชั่วโมง", "ชม.", "hour", "hours" -> Duration.ofHours(amount);
            default -> throw new IllegalArgumentException("unsupported relative reminder unit");
        };
        if (duration.toMinutes() > MAX_RELATIVE_MINUTES) {
            throw new IllegalArgumentException("relative reminder duration is too long");
        }
        return new RelativeDelay(duration);
    }

    private String title(String original, Matcher durationMatch) {
        String withoutDuration = (original.substring(0, durationMatch.start()) + " "
                + original.substring(durationMatch.end())).trim();
        String result = withoutDuration
                .replaceFirst("(?iu)^[,\\s]*(?:มินิคุง|มินิ[- ]?คุง|mini[- ]?kun)\\s*", "")
                .replaceFirst("(?iu)^[,\\s]*(?:ช่วย\\s*)?(?:ตั้ง\\s*)?(?:แจ้งเตือน|เตือน|ปลุก|บอก)"
                        + "(?:\\s*(?:เรา|ฉัน|ผม))?\\s*", "")
                .replaceFirst("(?iu)^\\s*(?:ว่า|ให้)\\s*", "")
                .replaceFirst("(?iu)(?:ให้หน่อย|ด้วยนะ|ด้วยครับ|ด้วยค่ะ|นะครับ|นะคะ|ครับ|ค่ะ|คะ|นะ|หน่อย)"
                        + "[.!?,，。!?\\s]*$", "")
                .trim();
        return result.isBlank() ? "แจ้งเตือน" : result;
    }

    private String normalizeDigits(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= '๐' && character <= '๙') {
                normalized.append((char) ('0' + character - '๐'));
            } else {
                normalized.append(character);
            }
        }
        return normalized.toString();
    }

    private String formatProposal(String title, ZonedDateTime startsAt, Object value) {
        StringBuilder content = new StringBuilder()
                .append("พี่สาวครับ มินิคุงคำนวณเวลาแจ้งเตือนให้แล้วนะครับ\n")
                .append("รายการ: ").append(title).append("\n")
                .append("เวลาที่จะแจ้งเตือน: ")
                .append(startsAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append("\n")
                .append("รายการนี้ยังไม่ได้บันทึกครับ พี่สาวยืนยันให้มินิคุงบันทึกไหมครับ");
        if (value instanceof Map<?, ?> result && result.get("proposed") != null) {
            content.append("\nรายละเอียดจาก planner.manage: ").append(result.get("proposed"));
        }
        return content.toString();
    }

    private record RelativeDelay(Duration duration) {
    }
}
