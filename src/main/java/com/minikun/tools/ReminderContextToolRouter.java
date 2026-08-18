package com.minikun.tools;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
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

import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.agent.minikun_agent.conversation.ConversationMemoryService;

/**
 * Classifies a reminder request using the current message and recent user context.
 * This covers the natural two-turn form where the schedule is stated first and
 * the user asks to create the reminder in a follow-up message.
 */
@Component
@ConditionalOnProperty(name = "minikun.planner.enabled", havingValue = "true", matchIfMissing = true)
public final class ReminderContextToolRouter implements ToolRequestRouter {
    private static final String TOOL_NAME = "planner.manage";
    private static final int MAX_RELATIVE_MINUTES = 7 * 24 * 60;
    private static final Pattern DAY_OF_MONTH = Pattern.compile(
            "(?iu)(?:วันที่|วันที|on\\s+the?)\\s*([0-9๐-๙]{1,2})");
    private static final Pattern CLOCK_TIME = Pattern.compile(
            "(?iu)(\\d{1,2})\\s*[:.]\\s*(\\d{2})\\s*(?:น\\.?|นาฬิกา)?");
    private static final Pattern SPOKEN_TIME = Pattern.compile(
            "(?iu)(\\d{1,2})\\s*(โมง|ทุ่ม|นาฬิกา)\\s*(เช้า|สาย|บ่าย|เย็น|ค่ำ|am|pm)?");
    private static final Pattern THAI_EARLY_MORNING_TIME = Pattern.compile(
            "(?iu)ตี\\s*(\\d{1,2})");
    private static final Pattern RELATIVE_TIME = Pattern.compile(
            "(?iu)(?:(?:ใน|ภายใน)\\s*)?อีก\\s*([0-9๐-๙]+)\\s*"
                    + "(วินาที|นาที|ชั่วโมง|ชม\\.?|seconds?|minutes?|hours?)"
                    + "|(?:in|after)\\s+([0-9]+)\\s*(seconds?|minutes?|hours?)");

    private final ToolExecutor executor;
    private final ConversationMemoryService conversationMemory;
    private final Clock clock;
    private final String defaultTimezone;

    public ReminderContextToolRouter(
            ToolExecutor executor,
            ConversationMemoryService conversationMemory,
            Clock clock,
            @Value("${minikun.time.default-zone:Asia/Bangkok}") String defaultTimezone) {
        this.executor = Objects.requireNonNull(executor, "tool executor must not be null");
        this.conversationMemory = Objects.requireNonNull(
                conversationMemory, "conversation memory must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.defaultTimezone = Objects.requireNonNullElse(defaultTimezone, "Asia/Bangkok");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        if (userText == null || userText.isBlank() || conversationId == null || !isReminderRequest(userText)) {
            return Optional.empty();
        }

        Optional<Schedule> currentSchedule = schedule(userText);
        if (currentSchedule.isPresent() && !isDayOfMonthSchedule(userText)) {
            // Existing relative/tomorrow routers handle these direct one-turn requests.
            return Optional.empty();
        }
        Schedule selected = currentSchedule.orElseGet(() -> recentSchedule(conversationId).orElse(null));
        if (selected == null) {
            return Optional.empty();
        }

        String title = title(selected.sourceText());
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("action", "create");
        arguments.put("title", title);
        arguments.put("note", selected.sourceText().trim());
        arguments.put("at", selected.startsAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        arguments.put("timezone", selected.startsAt().getZone().getId());
        arguments.put("remind_before_minutes", 0);
        arguments.put("recurrence", "NONE");

        String callId = "planner-context-route-" + UUID.randomUUID();
        ToolResult result = executor.execute(
                new ToolCallContext(conversationId, callId),
                new ToolCall(callId, TOOL_NAME, arguments));
        if (!result.success()) {
            return Optional.of(ToolEvidence.failed(TOOL_NAME,
                    "ขออภัยครับ ตั้งการแจ้งเตือนไม่สำเร็จ: " + result.error()));
        }
        return Optional.of(ToolEvidence.pendingConfirmation(
                TOOL_NAME, formatProposal(title, selected.startsAt(), result.value())));
    }

    private Optional<Schedule> recentSchedule(ConversationId conversationId) {
        List<ChatMessage> history = conversationMemory.load(conversationId);
        for (int index = history.size() - 1; index >= 0; index--) {
            ChatMessage message = history.get(index);
            if (!"user".equalsIgnoreCase(message.role())) {
                continue;
            }
            Optional<Schedule> candidate = schedule(message.content());
            if (candidate.isPresent()) {
                return candidate;
            }
        }
        return Optional.empty();
    }

    private Optional<Schedule> schedule(String sourceText) {
        String text = normalizeDigits(sourceText);
        Matcher relative = RELATIVE_TIME.matcher(text);
        if (relative.find()) {
            String amountText = relative.group(1) != null ? relative.group(1) : relative.group(3);
            String unit = relative.group(2) != null ? relative.group(2).toLowerCase(Locale.ROOT) : relative.group(4);
            long amount;
            try {
                amount = Long.parseLong(normalizeDigits(amountText));
            } catch (NumberFormatException exception) {
                return Optional.empty();
            }
            Duration delay = switch (unit) {
                case "วินาที", "second", "seconds" -> Duration.ofSeconds(amount);
                case "นาที", "minute", "minutes" -> Duration.ofMinutes(amount);
                case "ชั่วโมง", "ชม.", "hour", "hours" -> Duration.ofHours(amount);
                default -> Duration.ZERO;
            };
            if (amount <= 0 || delay.isZero() || delay.toMinutes() > MAX_RELATIVE_MINUTES) {
                return Optional.empty();
            }
            try {
                ZoneId relativeZone = ZoneId.of(defaultTimezone);
                return Optional.of(new Schedule(sourceText,
                        ZonedDateTime.now(clock.withZone(relativeZone)).plus(delay)));
            } catch (Exception exception) {
                return Optional.empty();
            }
        }
        Time time = time(text);
        if (time == null) {
            return Optional.empty();
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(defaultTimezone);
        } catch (Exception exception) {
            return Optional.empty();
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        Matcher dayMatcher = DAY_OF_MONTH.matcher(text);
        if (dayMatcher.find()) {
            int day = Integer.parseInt(dayMatcher.group(1));
            if (day < 1 || day > 31) {
                return Optional.empty();
            }
            ZonedDateTime candidate = now.withDayOfMonth(1).withHour(time.hour())
                    .withMinute(time.minute()).withSecond(0).withNano(0);
            int monthLength = candidate.toLocalDate().lengthOfMonth();
            if (day > monthLength) {
                return Optional.empty();
            }
            candidate = candidate.withDayOfMonth(day);
            if (!candidate.isAfter(now)) {
                candidate = candidate.plusMonths(1).withDayOfMonth(1);
                if (day > candidate.toLocalDate().lengthOfMonth()) {
                    return Optional.empty();
                }
                candidate = candidate.withDayOfMonth(day);
            }
            return Optional.of(new Schedule(sourceText, candidate));
        }

        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("พรุ่งนี้") || lower.contains("วันพรุ่งนี้") || lower.contains("tomorrow")) {
            return Optional.of(new Schedule(sourceText,
                    now.plusDays(1).withHour(time.hour()).withMinute(time.minute())
                            .withSecond(0).withNano(0)));
        }

        return Optional.empty();
    }

    private boolean isDayOfMonthSchedule(String text) {
        return DAY_OF_MONTH.matcher(normalizeDigits(text)).find();
    }

    private Time time(String text) {
        Matcher earlyMorningMatcher = THAI_EARLY_MORNING_TIME.matcher(text);
        if (earlyMorningMatcher.find()) {
            int hour = Integer.parseInt(earlyMorningMatcher.group(1));
            return hour >= 1 && hour <= 5 ? new Time(hour, 0) : null;
        }
        Matcher clockMatcher = CLOCK_TIME.matcher(text);
        if (clockMatcher.find()) {
            int hour = Integer.parseInt(clockMatcher.group(1));
            int minute = Integer.parseInt(clockMatcher.group(2));
            return validTime(hour, minute) ? new Time(hour, minute) : null;
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
        return validTime(hour, 0) ? new Time(hour, 0) : null;
    }

    private boolean validTime(int hour, int minute) {
        return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
    }

    private boolean isReminderRequest(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("แจ้งเตือน")
                || normalized.contains("ตั้งเตือน")
                || normalized.contains("เตือน")
                || normalized.contains("ปลุก")
                || normalized.contains("remind")
                || normalized.contains("notify")
                || normalized.contains("alarm");
    }

    private String title(String sourceText) {
        String result = sourceText
                .replaceFirst("(?iu)^[,\\s]*(?:มินิคุง|มินิ[- ]?คุง|mini[- ]?kun)\\s*", "")
                .replaceFirst("(?iu)^[,\\s]*(?:แล้วก็|และ|also|and)\\s*", "")
                .replaceFirst("(?iu)(?:วันที่|วันที|on\\s+the?)\\s*[0-9๐-๙]{1,2}", "")
                .replaceFirst("(?iu)(?:วัน)?พรุ่งนี้|tomorrow", "")
                .replaceFirst("(?iu)(?:อีก|ในอีก)\\s*[0-9๐-๙]+\\s*(?:วินาที|นาที|ชั่วโมง|ชม\\.?)", "")
                .replaceFirst("(?iu)(?:in|after)\\s+[0-9]+\\s*(?:seconds?|minutes?|hours?)", "")
                .replaceFirst("(?iu)(?:ตอน|เวลา|at)\\s*", "")
                .replaceFirst("(?iu)(?:\\d{1,2})\\s*[:.]\\s*\\d{2}\\s*(?:น\\.?|นาฬิกา)?", "")
                .replaceFirst("(?iu)ตี\\s*\\d{1,2}", "")
                .replaceFirst("(?iu)(?:\\d{1,2})\\s*(?:โมง|ทุ่ม|นาฬิกา)\\s*"
                        + "(?:เช้า|สาย|บ่าย|เย็น|ค่ำ|am|pm)?", "")
                .replaceFirst("(?iu)^[,\\s]*(?:มี|คือ|ว่า|ให้)\\s*", "")
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

    private record Schedule(String sourceText, ZonedDateTime startsAt) {
    }

    private record Time(int hour, int minute) {
    }
}
