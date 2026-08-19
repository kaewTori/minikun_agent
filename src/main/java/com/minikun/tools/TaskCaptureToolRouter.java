package com.minikun.tools;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.minikun.agent.minikun_agent.conversation.ConversationId;

/** Offers to capture an explicit task statement without silently persisting it. */
@Component
@Order(100)
@ConditionalOnProperty(name = "minikun.task.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(TaskManageTool.class)
public final class TaskCaptureToolRouter implements ToolRequestRouter {
    private static final String TOOL_NAME = "task.manage";
    private static final Pattern TASK_STATEMENT = Pattern.compile(
            "(?iu)^(?:มินิคุง[,\\s]*)?(?:ช่วย(?:จด|จำ|บันทึก)|ฝาก(?:จด|จำ|บันทึก|ไว้)|"
                    + "อย่าลืม|ต้อง(?=\\s)(?:ทำ|ไปทำ|ส่ง|จัดการ)?|เพิ่ม(?:งาน)?|สร้าง(?:งาน)?|todo\\s*:)"
                    + "\\s*(?:ว่า\\s*)?(.+?)\\s*[.!?,，。!?]*$");

    private final ToolExecutor executor;

    public TaskCaptureToolRouter(ToolExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "tool executor must not be null");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        return route(userText, conversationId, "default");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId) {
        if (!candidate(userText) || conversationId == null || ownerId == null || ownerId.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = TASK_STATEMENT.matcher(userText.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String title = cleanTitle(matcher.group(1));
        if (title.isBlank() || title.length() > 300) {
            return Optional.empty();
        }

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("action", "create");
        arguments.put("kind", "TASK");
        arguments.put("title", title);
        arguments.put("description", userText.trim());
        arguments.put("next_action", title);

        String callId = "task-capture-route-" + UUID.randomUUID();
        ToolResult result = executor.execute(
                new ToolCallContext(conversationId, callId, ownerId),
                new ToolCall(callId, TOOL_NAME, arguments));
        if (!result.success()) {
            return Optional.of(ToolEvidence.failed(TOOL_NAME,
                    "ขออภัยครับ มินิคุงเตรียมบันทึกงานนี้ไม่สำเร็จ: " + result.error()));
        }
        return Optional.of(ToolEvidence.pendingConfirmation(
                TOOL_NAME, proposal(title, result.value())));
    }

    private boolean candidate(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT).trim();
        if (normalized.contains("แจ้งเตือน") || normalized.contains("เตือน")
                || normalized.contains("ปลุก") || normalized.contains("remind")
                || normalized.contains("notify") || normalized.contains("alarm")) {
            return false;
        }
        return TASK_STATEMENT.matcher(text.trim()).matches();
    }

    private String cleanTitle(String value) {
        return value.replaceFirst("(?iu)^(?:ให้|ไว้)\\s*", "")
                .replaceFirst("(?iu)(?:ให้หน่อย|ด้วยนะ|ด้วยครับ|ด้วยค่ะ|นะครับ|นะคะ|ครับ|ค่ะ|คะ|นะ)$", "")
                .trim();
    }

    private String proposal(String title, Object value) {
        StringBuilder content = new StringBuilder("พี่สาวครับ มินิคุงพบว่านี่น่าจะเป็นงานที่ต้องติดตามนะครับ\n")
                .append("งาน: ").append(title)
                .append("\nงานนี้ยังไม่ได้บันทึกครับ ยืนยันให้มินิคุงเพิ่มเข้า task list ไหมครับ");
        if (value instanceof Map<?, ?> result && result.get("proposed") != null) {
            content.append("\nรายละเอียด: ").append(result.get("proposed"));
        }
        return content.toString();
    }
}
