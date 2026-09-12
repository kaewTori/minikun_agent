package com.minikun.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Routes daily investment/news questions to deterministic evidence before model generation. */
@Component
@ConditionalOnProperty(name = "minikun.investment.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(InvestmentMonitorTool.class)
public final class InvestmentMonitorRouter implements ToolRequestRouter {
    private static final String TOOL_NAME = "investment.monitor";
    private static final Pattern TARGET = Pattern.compile(
            "(?iu)(พอร์ต|หุ้น|การลงทุน|ตลาดทุน|ตลาดหุ้น|portfolio|holdings?|investment|market)");
    private static final Pattern ACTION = Pattern.compile(
            "(?iu)(ข่าว|วันนี้|ล่าสุด|ติดตาม|แนะนำ|มุมมอง|สรุป|แผน|monitor|news|latest|today|advice|brief|plan)");
    private static final Pattern PRICE_ONLY = Pattern.compile(
            "(?iu)^(?:(?:ดู|เช็ก|เช็ค|ขอ|show|get|check)\s*)?(?:ราคา|ราคาปัจจุบัน|ราคาล่าสุด|quote|price)"
                    + "(?:ของ|for)?\s*[A-Z][A-Z0-9.-]{0,7}\s*[.!?]?$");

    private final ToolExecutor executor;
    private final ObjectMapper objectMapper;

    public InvestmentMonitorRouter(ToolExecutor executor, ObjectMapper objectMapper) {
        this.executor = Objects.requireNonNull(executor, "tool executor must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        return route(userText, conversationId, "default");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId) {
        if (userText == null || userText.isBlank() || conversationId == null || !isMonitorRequest(userText)) {
            return Optional.empty();
        }
        boolean refresh = userText.matches("(?is).*?(วันนี้|ล่าสุด|latest|today|refresh).*?");
        String callId = "investment-monitor-" + UUID.randomUUID();
        ToolResult result = executor.execute(
                new ToolCallContext(conversationId, callId, ownerId),
                new ToolCall(callId, TOOL_NAME, Map.of("action", "daily_brief", "refresh", refresh)));
        if (!result.success()) {
            return Optional.of(ToolEvidence.failed(TOOL_NAME,
                    "ขออภัยครับ ตอนนี้ติดตามการลงทุนไม่สำเร็จ: " + result.error()));
        }
        try {
            return Optional.of(ToolEvidence.verified(TOOL_NAME,
                    "ข้อมูลแผนลงทุน ข่าว และพอร์ตที่ตรวจสอบได้:\n"
                            + objectMapper.writeValueAsString(result.value())));
        } catch (JsonProcessingException exception) {
            return Optional.of(ToolEvidence.failed(TOOL_NAME,
                    "ขออภัยครับ ผลติดตามการลงทุนมีรูปแบบข้อมูลไม่ถูกต้อง"));
        }
    }

    private boolean isMonitorRequest(String text) {
        String normalized = text.toLowerCase(Locale.ROOT).strip();
        return TARGET.matcher(normalized).find()
                && ACTION.matcher(normalized).find()
                && !PRICE_ONLY.matcher(normalized).matches();
    }
}
