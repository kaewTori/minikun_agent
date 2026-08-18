package com.minikun.tools;

import java.time.Clock;
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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.minikun.agent.minikun_agent.conversation.ConversationId;

/** Resolves clear calendar-style reminder requests without relying on model date arithmetic. */
@Component
@ConditionalOnProperty(name = "minikun.planner.enabled", havingValue = "true", matchIfMissing = true)
public final class AbsoluteReminderToolRouter implements ToolRequestRouter {
    private static final String TOOL_NAME = "planner.manage";
    private static final Pattern CLOCK_TIME = Pattern.compile(
            "(?iu)(\\d{1,2})\\s*[:.]\\s*(\\d{2})\\s*(?:น\\.?|นาฬิกา)?");
    private static final Pattern SPOKEN_TIME = Pattern.compile(
            "(?iu)(\\d{1,2})\\s*(โมง|ทุ่ม|นาฬิกา)\\s*(เช้า|สาย|บ่าย|เย็น|ค่ำ|am|pm)?");
    private static final Pattern THAI_EARLY_MORNING_TIME = Pattern.compile(
            "(?iu)ตี\\s*(\\d{1,2})");

    private final ToolExecutor executor;
    private final Clock clock;
    private final String defaultTimezone;

    public AbsoluteReminderToolRouter(
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
        String normalized = normalizeDigits(userText);
        if (!isTomorrow(normalized)) {
            return Optional.empty();
        }
        TimeMatch time = time(normalized);
        if (time == null) {
            return Optional.empty();
        }

        ZoneId zone;
        try {
            zone = ZoneId.of(defaultTimezone);
        } catch (Exception exception) {
            return Optional.of(ToolEvidence.failed(TOOL_NAME,
                    "ขออภัยครับ timezone ของระบบไม่ถูกต้อง: " + defaultTimezone));
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        ZonedDateTime startsAt = now.plusDays(1).withHour(time.hour()).withMinute(time.minute())
                .withSecond(0).withNano(0);
        String title = title(userText, time);

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("action", "create");
        arguments.put("title", title);
        arguments.put("note", userText.trim());
        arguments.put("at", startsAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        arguments.put("timezone", zone.getId());
        arguments.put("remind_before_minutes", 0);
        arguments.put("recurrence", "NONE");

        String callId = "planner-absolute-route-" + UUID.randomUUID();
        ToolResult result = executor.execute(
                new ToolCallContext(conversationId, callId),
                new ToolCall(callId, TOOL_NAME, arguments));
        if (!result.success()) {
            return Optional.of(ToolEvidence.failed(TOOL_NAME,
                    "ขออภัยครับ ตั้งการแจ้งเตือนไม่สำเร็จ: " + result.error()));
        }
        return Optional.of(ToolEvidence.pendingConfirmation(TOOL_NAME,
                formatProposal(title, startsAt, result.value())));
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

    private boolean isTomorrow(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("พรุ่งนี้")
                || normalized.contains("วันพรุ่งนี้")
                || normalized.contains("tomorrow");
    }

    private TimeMatch time(String text) {
        Matcher earlyMorningMatcher = THAI_EARLY_MORNING_TIME.matcher(text);
        if (earlyMorningMatcher.find()) {
            int hour = Integer.parseInt(earlyMorningMatcher.group(1));
            return hour >= 1 && hour <= 5
                    ? new TimeMatch(earlyMorningMatcher.start(), earlyMorningMatcher.end(), hour, 0)
                    : null;
        }
        Matcher clockMatcher = CLOCK_TIME.matcher(text);
        if (clockMatcher.find()) {
            int hour = Integer.parseInt(clockMatcher.group(1));
            int minute = Integer.parseInt(clockMatcher.group(2));
            return validTime(hour, minute) ? new TimeMatch(clockMatcher.start(), clockMatcher.end(), hour, minute)
                    : null;
        }
        Matcher spokenMatcher = SPOKEN_TIME.matcher(text);
        if (!spokenMatcher.find()) {
            return null;
        }
        int hour = Integer.parseInt(spokenMatcher.group(1));
        String unit = spokenMatcher.group(2).toLowerCase(Locale.ROOT);
        String period = spokenMatcher.group(3) == null ? "" : spokenMatcher.group(3).toLowerCase(Locale.ROOT);
        if ("ทุ่ม".equals(unit)) {
            hour = hour == 12 ? 0 : hour >= 1 && hour <= 5 ? hour + 18 : -1;
        } else if (("บ่าย".equals(period) || "เย็น".equals(period) || "ค่ำ".equals(period)
                || "pm".equals(period)) && hour < 12) {
            hour += 12;
        }
        return validTime(hour, 0)
                ? new TimeMatch(spokenMatcher.start(), spokenMatcher.end(), hour, 0) : null;
    }

    private boolean validTime(int hour, int minute) {
        return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
    }

    private String title(String original, TimeMatch time) {
        String withoutTime = (original.substring(0, time.start()) + " "
                + original.substring(time.end())).trim();
        String result = withoutTime
                .replaceFirst("(?iu)^[,\\s]*(?:มินิคุง|มินิ[- ]?คุง|mini[- ]?kun)\\s*", "")
                .replaceFirst("(?iu)(?:วัน)?พรุ่งนี้|tomorrow", "")
                .replaceFirst("(?iu)^[,\\s]*(?:ตอน|เวลา)\\s*", "")
                .replaceFirst("(?iu)ตี\\s*\\d{1,2}", "")
                .replaceFirst("(?iu)^[,\\s]*(?:ช่วย\\s*)?(?:ตั้ง\\s*)?(?:แจ้งเตือน|เตือน|ปลุก|บอก)"
                        + "(?:\\s*(?:เรา|ฉัน|ผม))?\\s*", "")
                .replaceFirst("(?iu)^\\s*(?:(?:ว่า|ให้)\\s*)+", "")
                .replaceFirst("(?iu)\\s+ให้(?:\\s*(?:เรา|ฉัน|ผม))?\\s*(?:(?:หน่อย|นะ|ครับ|ค่ะ|คะ|ด้วย))+"
                        + "[.!?,，。!?\\s]*$", "")
                .trim();
        return result.isBlank() ? "แจ้งเตือน" : result;
    }

    private String normalizeDigits(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            normalized.append(character >= '๐' && character <= '๙'
                    ? (char) ('0' + character - '๐') : character);
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

    private record TimeMatch(int start, int end, int hour, int minute) {
    }
}
