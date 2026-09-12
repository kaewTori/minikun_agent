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

/** Routes natural long-term portfolio review requests to the read-only investment review. */
@Component
@ConditionalOnProperty(name = "minikun.investment.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(InvestmentAnalyzeTool.class)
public final class InvestmentReviewRouter implements ToolRequestRouter {
    private static final String TOOL_NAME = "investment.analyze";
    private static final Pattern TARGET = Pattern.compile(
            "(?iu)(พอร์ต|หุ้นที่ถือ|การลงทุน|portfolio|holdings?|investment)");
    private static final Pattern ACTION = Pattern.compile(
            "(?iu)(ทบทวน|วิเคราะห์|ตรวจสอบ|ตรวจ|เช็ก|เช็ค|ดู|สรุป|ประเมิน|"
                    + "review|analy[sz]e|check|show|summary)");
    private static final Pattern MONITOR_SIGNAL = Pattern.compile(
            "(?iu)(ข่าว|วันนี้|ล่าสุด|ติดตาม|แนะนำ|มุมมอง|แผน|monitor|news|latest|today|advice|brief|plan)");

    private final ToolExecutor executor;
    private final ObjectMapper objectMapper;

    public InvestmentReviewRouter(ToolExecutor executor, ObjectMapper objectMapper) {
        this.executor = Objects.requireNonNull(executor, "tool executor must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        return route(userText, conversationId, "default");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId) {
        if (userText == null || userText.isBlank() || conversationId == null || !isReviewRequest(userText)) {
            return Optional.empty();
        }
        String callId = "investment-review-" + UUID.randomUUID();
        ToolResult result = executor.execute(
                new ToolCallContext(conversationId, callId, ownerId),
                new ToolCall(callId, TOOL_NAME, Map.of("action", "review")));
        if (!result.success()) {
            return Optional.of(ToolEvidence.failed(TOOL_NAME,
                    "ขออภัยครับ ตอนนี้ทบทวนพอร์ตไม่สำเร็จ: " + result.error()));
        }
        try {
            return Optional.of(ToolEvidence.verified(TOOL_NAME,
                    "ข้อมูลจากการทบทวนพอร์ตระยะยาว:\n" + objectMapper.writeValueAsString(result.value())));
        } catch (JsonProcessingException exception) {
            return Optional.of(ToolEvidence.failed(TOOL_NAME,
                    "ขออภัยครับ ผลการทบทวนพอร์ตมีรูปแบบข้อมูลไม่ถูกต้อง"));
        }
    }

    private boolean isReviewRequest(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return TARGET.matcher(normalized).find() && ACTION.matcher(normalized).find()
                && !MONITOR_SIGNAL.matcher(normalized).find();
    }
}
