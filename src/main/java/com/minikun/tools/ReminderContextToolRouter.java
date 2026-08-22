package com.minikun.tools;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
    private static final Pattern ISO_DATE = Pattern.compile(
            "(?<![0-9])([0-9]{4}-[0-9]{2}-[0-9]{2})(?![0-9])");
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
    private static final Pattern REMINDER_PAYLOAD = Pattern.compile(
            "(?iu)(?:แจ้งเตือน|เตือน|ปลุก|remind|notify|alarm).*?(?:ว่า|about)\\s*(.+)$");

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
        if (userText == null || userText.isBlank() || conversationId == null) {
            return Optional.empty();
        }

        ZoneId zone;
        try {
            zone = ZoneId.of(defaultTimezone);
        } catch (Exception exception) {
            return Optional.of(ToolEvidence.finalFailed(TOOL_NAME,
                    "ขออภัยครับ timezone ของระบบไม่ถูกต้อง: " + defaultTimezone));
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        ReminderParts current = parts(userText, now);
        boolean currentReminder = isReminderRequest(userText);
        if (currentReminder && current.relativeStartsAt() != null) {
            // Existing relative router owns direct one-turn requests.
            return Optional.empty();
        }
        if (currentReminder && current.complete(now) && isTomorrow(userText)) {
            // Existing relative/tomorrow routers handle these direct one-turn requests.
            return Optional.empty();
        }

        Optional<String> previousUserText = latestUserText(conversationId);
        ReminderParts previous = previousUserText.map(text -> parts(text, now)).orElse(ReminderParts.empty());
        boolean continuation = !currentReminder
                && previousUserText.map(this::isReminderRequest).orElse(false)
                && current.hasScheduleInformation();
        if (!currentReminder && !continuation) {
            return Optional.empty();
        }

        ReminderParts combined = currentReminder
                ? current.mergeMissingFrom(previous)
                : previous.mergeMissingFrom(current);
        String sourceText = sourceText(userText, currentReminder, current, previousUserText);
        Optional<Schedule> selected = combined.schedule(sourceText, now);
        if (selected.isEmpty()) {
            return Optional.of(clarification(combined));
        }
        if (!selected.get().startsAt().isAfter(now)) {
            return Optional.of(ToolEvidence.finalVerified(TOOL_NAME,
                    "เวลาที่ระบุผ่านไปแล้วครับ กรุณาระบุวันหรือเวลาใหม่สำหรับการแจ้งเตือน"));
        }

        String title = title(selected.get().sourceText());
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("action", "create");
        arguments.put("title", title);
        arguments.put("note", selected.get().sourceText().trim());
        arguments.put("at", selected.get().startsAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        arguments.put("timezone", selected.get().startsAt().getZone().getId());
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
                TOOL_NAME, formatProposal(title, selected.get().startsAt(), result.value())));
    }

    private Optional<String> latestUserText(ConversationId conversationId) {
        List<ChatMessage> history = conversationMemory.load(conversationId);
        for (int index = history.size() - 1; index >= 0; index--) {
            ChatMessage message = history.get(index);
            if ("user".equalsIgnoreCase(message.role())) {
                return Optional.of(message.content());
            }
        }
        return Optional.empty();
    }

    private ReminderParts parts(String sourceText, ZonedDateTime now) {
        String text = normalizeDigits(sourceText);
        Matcher relative = RELATIVE_TIME.matcher(text);
        if (relative.find()) {
            String amountText = relative.group(1) != null ? relative.group(1) : relative.group(3);
            String unit = relative.group(2) != null ? relative.group(2).toLowerCase(Locale.ROOT) : relative.group(4);
            long amount;
            try {
                amount = Long.parseLong(normalizeDigits(amountText));
            } catch (NumberFormatException exception) {
                return ReminderParts.empty();
            }
            Duration delay = switch (unit) {
                case "วินาที", "second", "seconds" -> Duration.ofSeconds(amount);
                case "นาที", "minute", "minutes" -> Duration.ofMinutes(amount);
                case "ชั่วโมง", "ชม.", "hour", "hours" -> Duration.ofHours(amount);
                default -> Duration.ZERO;
            };
            if (amount <= 0 || delay.isZero() || delay.toMinutes() > MAX_RELATIVE_MINUTES) {
                return ReminderParts.empty();
            }
            return new ReminderParts(null, null, now.plus(delay));
        }
        return new ReminderParts(date(text, now), time(text), null);
    }

    private DatePart date(String text, ZonedDateTime now) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("มะรืน") || lower.contains("day after tomorrow")) {
            return DatePart.exact(now.toLocalDate().plusDays(2));
        }
        if (lower.contains("พรุ่งนี้") || lower.contains("วันพรุ่งนี้") || lower.contains("tomorrow")) {
            return DatePart.exact(now.toLocalDate().plusDays(1));
        }
        if (lower.contains("วันนี้") || lower.contains("today")) {
            return DatePart.exact(now.toLocalDate());
        }
        Matcher isoDate = ISO_DATE.matcher(text);
        if (isoDate.find()) {
            try {
                return DatePart.exact(LocalDate.parse(isoDate.group(1), DateTimeFormatter.ISO_LOCAL_DATE));
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        Matcher dayMatcher = DAY_OF_MONTH.matcher(text);
        if (dayMatcher.find()) {
            int day = Integer.parseInt(dayMatcher.group(1));
            return day >= 1 && day <= 31 ? DatePart.dayOfMonth(day) : null;
        }
        return null;
    }

    private boolean isTomorrow(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("พรุ่งนี้") || normalized.contains("วันพรุ่งนี้")
                || normalized.contains("tomorrow");
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

    private String sourceText(
            String currentText,
            boolean currentReminder,
            ReminderParts current,
            Optional<String> previousUserText) {
        if (currentReminder && (current.hasScheduleInformation() || previousUserText.isEmpty())) {
            return currentText;
        }
        return previousUserText.orElse(currentText);
    }

    private ToolEvidence clarification(ReminderParts parts) {
        if (parts.time() == null && parts.date() == null) {
            return ToolEvidence.finalVerified(TOOL_NAME,
                    "มินิคุงตั้งการแจ้งเตือนได้ครับ ต้องการให้เตือนวันไหนและเวลาเท่าไรครับ");
        }
        if (parts.date() == null) {
            return ToolEvidence.finalVerified(TOOL_NAME,
                    "มินิคุงตั้งการแจ้งเตือนได้ครับ ต้องการให้เตือนวันที่ไหน เวลา "
                            + formatTime(parts.time()) + " น. ครับ");
        }
        return ToolEvidence.finalVerified(TOOL_NAME,
                "มินิคุงตั้งการแจ้งเตือนได้ครับ ต้องการให้เตือนเวลาเท่าไรครับ");
    }

    private String formatTime(Time time) {
        return String.format(Locale.ROOT, "%02d:%02d", time.hour(), time.minute());
    }

    private String title(String sourceText) {
        Matcher payload = REMINDER_PAYLOAD.matcher(sourceText);
        if (payload.find() && !payload.group(1).isBlank()) {
            return trimPoliteEnding(payload.group(1));
        }
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
                        + "[.!?,，。!?\\s]*$", "");
        result = trimPoliteEnding(result);
        return result.isBlank() ? "แจ้งเตือน" : result;
    }

    private String trimPoliteEnding(String value) {
        return value.replaceFirst(
                "(?iu)(?:ให้หน่อย|ด้วยนะ|ด้วยครับ|ด้วยค่ะ|นะครับ|นะคะ|ครับ|ค่ะ|คะ|นะ|หน่อย)"
                        + "[.!?,，。!?\\s]*$", "").trim();
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

    private record ReminderParts(DatePart date, Time time, ZonedDateTime relativeStartsAt) {
        private static ReminderParts empty() {
            return new ReminderParts(null, null, null);
        }

        private boolean hasScheduleInformation() {
            return date != null || time != null || relativeStartsAt != null;
        }

        private boolean complete(ZonedDateTime now) {
            return schedule("reminder", now).isPresent();
        }

        private ReminderParts mergeMissingFrom(ReminderParts fallback) {
            if (relativeStartsAt != null) {
                return this;
            }
            if (date == null && time == null && fallback.relativeStartsAt != null) {
                return fallback;
            }
            return new ReminderParts(
                    date != null ? date : fallback.date,
                    time != null ? time : fallback.time,
                    null);
        }

        private Optional<Schedule> schedule(String sourceText, ZonedDateTime now) {
            if (relativeStartsAt != null) {
                return Optional.of(new Schedule(sourceText, relativeStartsAt));
            }
            if (date == null || time == null) {
                return Optional.empty();
            }
            LocalDate resolvedDate = date.resolve(time, now);
            if (resolvedDate == null) {
                return Optional.empty();
            }
            return Optional.of(new Schedule(sourceText,
                    resolvedDate.atTime(time.hour(), time.minute()).atZone(now.getZone())));
        }
    }

    private record DatePart(LocalDate exactDate, Integer dayOfMonth) {
        private static DatePart exact(LocalDate date) {
            return new DatePart(date, null);
        }

        private static DatePart dayOfMonth(int day) {
            return new DatePart(null, day);
        }

        private LocalDate resolve(Time time, ZonedDateTime now) {
            if (exactDate != null) {
                return exactDate;
            }
            LocalTime requestedTime = LocalTime.of(time.hour(), time.minute());
            YearMonth month = YearMonth.from(now);
            for (int offset = 0; offset <= 12; offset++) {
                YearMonth candidateMonth = month.plusMonths(offset);
                if (dayOfMonth > candidateMonth.lengthOfMonth()) {
                    continue;
                }
                LocalDate candidate = candidateMonth.atDay(dayOfMonth);
                if (candidate.isAfter(now.toLocalDate())
                        || candidate.isEqual(now.toLocalDate()) && requestedTime.isAfter(now.toLocalTime())) {
                    return candidate;
                }
            }
            return null;
        }
    }

    private record Time(int hour, int minute) {
    }
}
