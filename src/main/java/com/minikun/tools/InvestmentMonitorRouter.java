package com.minikun.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.investment.PortfolioPosition;
import com.minikun.investment.PortfolioSummary;
import java.math.BigDecimal;
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
    private static final Pattern PORTFOLIO_FACT = Pattern.compile(
            "(?iu)(?:(?:พอร์ต|port(?:folio)?).*(?:มี.*(?:หุ้น|อะไร)|ถือ(?:หุ้น)?(?:อะไร|บ้าง)|ประกอบด้วย|รายการ)|"
                    + "รายการหุ้น|หุ้นที่(?:เรา|ฉัน)?ถือ|ถือหุ้น|"
                    + "(?:what|which).*(?:stocks?|shares?|positions?|hold|own|portfolio))");
    private static final Pattern FRESH_MONITOR_SIGNAL = Pattern.compile(
            "(?iu)(ข่าว|วันนี้|ล่าสุด|ติดตาม|แนะนำ|มุมมอง|monitor|news|latest|today|advice|brief)");
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
        boolean portfolioFact = isPortfolioFactRequest(userText);
        boolean refresh = !portfolioFact && userText.matches("(?is).*?(วันนี้|ล่าสุด|latest|today|refresh).*?");
        String action = portfolioFact ? "plan" : "daily_brief";
        String callId = "investment-monitor-" + UUID.randomUUID();
        ToolResult result = executor.execute(
                new ToolCallContext(conversationId, callId, ownerId),
                new ToolCall(callId, TOOL_NAME, portfolioFact
                        ? Map.of("action", action)
                        : Map.of("action", action, "refresh", refresh)));
        if (!result.success()) {
            String content = "ขออภัยครับ ตอนนี้ตรวจสอบพอร์ตไม่สำเร็จ: " + result.error();
            return Optional.of(portfolioFact
                    ? ToolEvidence.finalFailed(TOOL_NAME, content)
                    : ToolEvidence.failed(TOOL_NAME, "ขออภัยครับ ตอนนี้ติดตามการลงทุนไม่สำเร็จ: " + result.error()));
        }
        try {
            if (portfolioFact) {
                return Optional.of(ToolEvidence.finalVerified(TOOL_NAME, formatPortfolio(result.value(), ownerId)));
            }
            return Optional.of(ToolEvidence.verified(TOOL_NAME,
                    "ข้อมูลแผนลงทุน ข่าว และพอร์ตที่ตรวจสอบได้:\n"
                            + objectMapper.writeValueAsString(result.value())));
        } catch (JsonProcessingException exception) {
            return Optional.of(ToolEvidence.failed(TOOL_NAME,
                    "ขออภัยครับ ผลติดตามการลงทุนมีรูปแบบข้อมูลไม่ถูกต้อง"));
        }
    }

    private String formatPortfolio(Object value, String ownerId) {
        if (!(value instanceof Map<?, ?> report) || !(report.get("portfolio") instanceof PortfolioSummary portfolio)) {
            return "ตรวจสอบ ledger ของพอร์ตแล้ว แต่ผลข้อมูลไม่อยู่ในรูปแบบที่แสดงได้ จึงไม่ขอเดารายการหุ้นครับ\n"
                    + "owner_id: " + ownerId;
        }
        StringBuilder content = new StringBuilder("ข้อมูลพอร์ตจาก ledger (owner_id: ")
                .append(ownerId).append(")\n");
        if (portfolio.positions().isEmpty()) {
            return content.append("ยังไม่พบรายการถือครองที่บันทึกไว้ครับ").toString();
        }
        content.append("ถืออยู่ ").append(portfolio.positions().size()).append(" รายการ\n");
        for (PortfolioPosition position : portfolio.positions()) {
            content.append("• ").append(position.symbol())
                    .append(": ").append(position.quantity())
                    .append(" หน่วย | ต้นทุนเฉลี่ย ").append(position.averageCost())
                    .append(" ").append(position.currency())
                    .append(" | ต้นทุนรวม ").append(position.costBasis())
                    .append(" ").append(portfolio.baseCurrency()).append("\n");
        }
        content.append("ต้นทุนคงค้างรวม: ").append(portfolio.totalOpenCostBasis())
                .append(" ").append(portfolio.baseCurrency());
        BigDecimal realized = portfolio.realizedProfitLoss();
        if (realized.signum() != 0) content.append("\nกำไร/ขาดทุนที่รับรู้: ").append(realized)
                .append(" ").append(portfolio.baseCurrency());
        return content.toString();
    }

    private boolean isMonitorRequest(String text) {
        String normalized = text.toLowerCase(Locale.ROOT).strip();
        boolean portfolioFact = isPortfolioFactRequest(normalized);
        return (TARGET.matcher(normalized).find() || portfolioFact)
                && (ACTION.matcher(normalized).find() || portfolioFact)
                && !PRICE_ONLY.matcher(normalized).matches();
    }

    private boolean isPortfolioFactRequest(String text) {
        String normalized = text.toLowerCase(Locale.ROOT).strip();
        return PORTFOLIO_FACT.matcher(normalized).find()
                && !FRESH_MONITOR_SIGNAL.matcher(normalized).find();
    }
}
