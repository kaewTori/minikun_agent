package com.minikun.tools;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.minikun.agent.minikun_agent.conversation.ConversationId;

/** Routes unambiguous local time questions through the native time tool. */
@Component
public final class CurrentTimeToolRouter implements ToolRequestRouter {
    private static final String TOOL_NAME = "time.get_current_time";
    private final ToolExecutor executor;

    public CurrentTimeToolRouter(ToolExecutor executor) {
        this.executor = java.util.Objects.requireNonNull(executor, "tool executor must not be null");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        if (userText == null || userText.isBlank() || conversationId == null || !isTimeQuestion(userText)
                || hasExplicitTimezone(userText)) {
            return Optional.empty();
        }
        String callId = "time-route-" + java.util.UUID.randomUUID();
        ToolResult result = executor.execute(
                new ToolCallContext(conversationId, callId),
                new ToolCall(callId, TOOL_NAME, Map.of()));
        if (!result.success()) {
            return Optional.of(ToolEvidence.failed(TOOL_NAME,
                    "ขออภัยครับ ตอนนี้เรียกใช้เครื่องมือดูเวลาไม่สำเร็จ: " + result.error()));
        }
        if (!(result.value() instanceof Map<?, ?> values)) {
            return Optional.of(ToolEvidence.failed(TOOL_NAME,
                    "ขออภัยครับ ข้อมูลเวลาที่ได้รับมีรูปแบบไม่ถูกต้อง"));
        }
        return Optional.of(ToolEvidence.verified(TOOL_NAME, format(values)));
    }

    private boolean isTimeQuestion(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("กี่โมง")
                || normalized.contains("เวลาเท่าไหร่")
                || normalized.contains("เวลาเท่าไร")
                || normalized.contains("วันที่เท่าไหร่")
                || normalized.contains("วันที่เท่าไร")
                || normalized.contains("วันอะไร")
                || normalized.contains("what time")
                || normalized.contains("what date")
                || normalized.contains("what day")
                || normalized.contains("current time")
                || normalized.contains("today's date");
    }

    private boolean hasExplicitTimezone(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.matches(".*\\b[a-z]+/[a-z_]+\\b.*")
                || normalized.contains("ลอนดอน")
                || normalized.contains("london")
                || normalized.contains("โตเกียว")
                || normalized.contains("tokyo")
                || normalized.contains("นิวยอร์ก")
                || normalized.contains("new york")
                || normalized.contains("นิวยอรก");
    }

    private String format(Map<?, ?> values) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        values.forEach((key, value) -> ordered.put(String.valueOf(key), value));
        StringBuilder content = new StringBuilder("ข้อมูลเวลาปัจจุบันจาก ").append(TOOL_NAME).append("\n");
        append(content, "เขตเวลา", ordered.get("timezone"));
        append(content, "วันที่", ordered.get("localDate"));
        append(content, "เวลา", ordered.get("localTime"));
        append(content, "วัน", ordered.get("dayOfWeek"));
        append(content, "UTC offset", ordered.get("utcOffset"));
        append(content, "ISO 8601", ordered.get("iso8601"));
        return content.toString().trim();
    }

    private void append(StringBuilder content, String label, Object value) {
        if (value != null && !value.toString().isBlank()) {
            content.append(label).append(": ").append(value).append("\n");
        }
    }
}
