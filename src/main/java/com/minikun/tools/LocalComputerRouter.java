package com.minikun.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
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

/** Routes explicit, read-only local-computer requests without relying on model tool selection. */
@Component
@Order(40)
@ConditionalOnProperty(name = "minikun.computer.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(LocalComputerTool.class)
public final class LocalComputerRouter implements ToolRequestRouter {
    private static final String TOOL_NAME = "computer.local";
    private static final Pattern ACTION = Pattern.compile(
            "(?iu)\\baction\\s*(?:=|:|เป็น|คือ)?\\s*"
                    + "(roots|list|search|read|inspect_folder|clipboard|applications|workflows|audit)\\b");
    private static final Pattern ROOT = Pattern.compile(
            "(?iu)\\broot\\s*(?:=|:|เป็น|คือ)?\\s*([a-zA-Z0-9_-]{1,40})\\b");
    private static final Pattern PATH = Pattern.compile(
            "(?iu)\\bpath\\s*(?:=|:|เป็น|คือ)?\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s,]+))");
    private static final Pattern QUERY = Pattern.compile(
            "(?iu)\\bquery\\s*(?:=|:|เป็น|คือ)?\\s*(?:\"([^\"]+)\"|'([^']+)'|([^,]+))");
    private static final Pattern LIMIT = Pattern.compile("(?iu)\\blimit\\s*(?:=|:)?\\s*(\\d{1,3})\\b");

    private final ToolExecutor executor;
    private final ObjectMapper objectMapper;

    public LocalComputerRouter(ToolExecutor executor, ObjectMapper objectMapper) {
        this.executor = Objects.requireNonNull(executor, "tool executor must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        return route(userText, conversationId, "default");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId) {
        if (userText == null || userText.isBlank() || conversationId == null) return Optional.empty();
        Optional<String> selectedAction = action(userText);
        if (selectedAction.isEmpty()) return Optional.empty();

        Map<String, Object> arguments = arguments(userText, selectedAction.get());
        if (!hasRequiredArguments(selectedAction.get(), arguments)) return Optional.empty();
        String callId = "computer-route-" + UUID.randomUUID();
        ToolResult result = executor.execute(new ToolCallContext(conversationId, callId, ownerId),
                new ToolCall(callId, TOOL_NAME, arguments));
        if (!result.success()) {
            return Optional.of(ToolEvidence.finalFailed(TOOL_NAME,
                    "ขออภัยครับ มินิคุงทำรายการบนเครื่องไม่สำเร็จ: " + result.error()));
        }
        return Optional.of(ToolEvidence.finalVerified(TOOL_NAME, format(selectedAction.get(), result.value())));
    }

    private Optional<String> action(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        Matcher explicit = ACTION.matcher(text);
        if (normalized.contains(TOOL_NAME) && explicit.find()) return Optional.of(explicit.group(1).toLowerCase(Locale.ROOT));
        if ((normalized.contains("โฟลเดอร์") || normalized.contains("folder") || normalized.contains("root"))
                && (normalized.contains("เข้าถึง") || normalized.contains("อนุญาต")
                        || normalized.contains("access"))) return Optional.of("roots");
        if (normalized.contains("คลิปบอร์ด") || normalized.contains("clipboard")) {
            if (normalized.contains("อ่าน") || normalized.contains("ดู") || normalized.contains("read")
                    || normalized.contains("show")) return Optional.of("clipboard");
        }
        if (normalized.contains(TOOL_NAME) && normalized.contains("applications")) return Optional.of("applications");
        if (normalized.contains(TOOL_NAME) && normalized.contains("workflows")) return Optional.of("workflows");
        return Optional.empty();
    }

    private Map<String, Object> arguments(String text, String action) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);
        capture(ROOT, text).ifPresent(value -> result.put("root", value));
        capture(PATH, text).ifPresent(value -> result.put("path", "ว่าง".equals(value) ? "" : value));
        capture(QUERY, text).ifPresent(value -> result.put("query", value));
        capture(LIMIT, text).ifPresent(value -> result.put("limit", Integer.parseInt(value)));
        return result;
    }

    private Optional<String> capture(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return Optional.empty();
        for (int index = 1; index <= matcher.groupCount(); index++) {
            if (matcher.group(index) != null) return Optional.of(matcher.group(index).trim());
        }
        return Optional.empty();
    }

    private boolean hasRequiredArguments(String action, Map<String, Object> arguments) {
        if ("list".equals(action) || "read".equals(action) || "inspect_folder".equals(action)) {
            return arguments.containsKey("root") && arguments.containsKey("path");
        }
        if ("search".equals(action)) {
            return arguments.containsKey("root") && arguments.containsKey("path") && arguments.containsKey("query");
        }
        return true;
    }

    private String format(String action, Object value) {
        try {
            return "ผลจาก Local Computer Agent (" + action + "):\n"
                    + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "ผลจาก Local Computer Agent (" + action + "): " + String.valueOf(value);
        }
    }
}
