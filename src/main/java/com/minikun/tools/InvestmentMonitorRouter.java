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
import org.springframework.core.annotation.Order;

/** Routes daily investment/news questions to deterministic evidence before model generation. */
@Component
@Order(10)
@ConditionalOnProperty(name = "minikun.investment.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(InvestmentMonitorTool.class)
public final class InvestmentMonitorRouter implements ToolRequestRouter {
    private static final String TOOL_NAME = "investment.monitor";
    private static final Pattern THESIS_REPLY = Pattern.compile(
            "(?isu)^\\s*(?:บันทึก\\s*)?เหตุผลที่ถือ\\s+([A-Z][A-Z0-9.-]{0,7})\\s*[:：]\\s*(.+?)"
                    + "\\s*[;；\\n]\\s*ทบทวนเมื่อ\\s*[:：]\\s*(.+?)\\s*$");
    private static final Pattern TARGET = Pattern.compile(
            "(?iu)(พอร์ต|หุ้น|การลงทุน|ตลาดทุน|ตลาดหุ้น|portfolio|holdings?|investment|market)");
    private static final Pattern ACTION = Pattern.compile(
            "(?iu)(ข่าว|วันนี้|ล่าสุด|ติดตาม|แนะนำ|มุมมอง|สรุป|แผน|monitor|news|latest|today|advice|brief|plan)");
    private static final Pattern MARKET_ANALYSIS = Pattern.compile(
            "(?iu)(?:(?:วิเคราะห์|ประเมิน|ศึกษา|research|analy[sz]e|analysis)\\s*(?:the\\s+)?"
                    + "(?:ตลาด(?:หุ้น|ทุน)?|stock\\s*market|market)|"
                    + "(?:ตลาดหุ้น|ตลาดทุน|stock\\s*market|market)\\s*(?:วิเคราะห์|analysis|research|analy[sz]e))");
    private static final Pattern PORTFOLIO_TARGET = Pattern.compile(
            "(?iu)(?:พอร์ต|\\bport(?:folio)?\\b|\\bholdings?\\b|\\bpositions?\\b|"
                    + "หุ้นที่(?:เรา|ฉัน|ผม)?(?:กำลัง)?ถือ|หุ้นในพอร์ต|รายการ(?:หุ้น|ลงทุน)|"
                    + "(?:ข้อมูล|รายละเอียด|สถานะ|ภาพรวม)\\s*(?:ของ\\s*)?(?:พอร์ต|การลงทุน)|"
                    + "(?:portfolio|holdings?|positions?)\\s*(?:ของ|ของฉัน|ของเรา)?)");
    private static final Pattern PORTFOLIO_OWNERSHIP = Pattern.compile(
            "(?iu)(?:เราถือ|ฉันถือ|ผมถือ|ในพอร์ต|\\bmy\\b|"
                    + "\\b(?:i|we)\\s+(?:hold|own)\\b)");
    private static final Pattern PORTFOLIO_INVENTORY = Pattern.compile(
            "(?iu)(?:มี|ถือ|อยู่|อะไร|ไหน|บ้าง|รายการ|ตัวไหน|ตัวใด|ประกอบ(?:ด้วย)?|แสดง|ดู|"
                    + "เช็ก|เช็ค|ตรวจสอบ|ข้อมูล|รายละเอียด|สถานะ|ภาพรวม|สรุป|เป็นยังไง|เป็นอย่างไร|"
                    + "ตอนนี้|ปัจจุบัน|what|which|show|list|have|hold|own|currently|current|overview|status|details?)");
    private static final Pattern PORTFOLIO_ANALYSIS_SIGNAL = Pattern.compile(
            "(?iu)(วิเคราะห์|ทบทวน|แนะนำ|มุมมอง|ความเสี่ยง|ผลตอบแทน|กำไร|ขาดทุน|สัดส่วน|"
                    + "ซื้อ|ขาย|ควร|ข่าว|ราคา|มูลค่า|review|analy[sz]e|recommend|risk|performance|"
                    + "return|profit|loss|allocation|buy|sell|price|quote|valuation|news)");
    private static final Pattern FRESH_MONITOR_SIGNAL = Pattern.compile(
            "(?iu)(ข่าว|วันนี้|ล่าสุด|ติดตาม|แนะนำ|มุมมอง|monitor|news|latest|today|advice|brief)");
    private static final Pattern PRICE_ONLY = Pattern.compile(
            "(?iu)^(?:(?:ดู|เช็ก|เช็ค|ขอ|show|get|check)\s*)?(?:ราคา|ราคาปัจจุบัน|ราคาล่าสุด|quote|price)"
                    + "(?:ของ|for)?\s*[A-Z][A-Z0-9.-]{0,7}\s*[.!?]?$");

    private final ToolExecutor executor;
    private final ObjectMapper objectMapper;

    public InvestmentMonitorRouter(ToolExecutor executor, ObjectMapper objectMapper) {
        this.executor = Objects.requireNonNull(executor, "tool executor must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null")
                .copy().findAndRegisterModules();
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        return route(userText, conversationId, "default");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId) {
        if (userText != null && conversationId != null) {
            var thesis = THESIS_REPLY.matcher(userText);
            if (thesis.matches()) {
                if (thesis.group(2).strip().matches("[.\\s…]+") || thesis.group(3).strip().matches("[.\\s…]+"))
                    return Optional.of(ToolEvidence.finalVerified("investment.manage",
                            "เติมเหตุผลที่ถือและเงื่อนไขทบทวนจริงแทนจุดไข่ปลาก่อนครับ ยังไม่ได้บันทึกข้อมูล"));
                String callId = "investment-thesis-" + UUID.randomUUID();
                ToolResult proposal = executor.execute(new ToolCallContext(conversationId, callId, ownerId),
                        new ToolCall(callId, "investment.manage", Map.of("action", "save_thesis",
                                "symbol", thesis.group(1).toUpperCase(Locale.ROOT), "summary", thesis.group(2).strip(),
                                "invalidation", thesis.group(3).strip())));
                if (!proposal.success()) return Optional.of(ToolEvidence.finalFailed("investment.manage",
                        "เตรียมบันทึกเหตุผลการถือไม่สำเร็จครับ: " + proposal.error()));
                return Optional.of(ToolEvidence.pendingConfirmation("investment.manage",
                        "พี่สาวต้องการบันทึกเหตุผลที่ถือ " + thesis.group(1).toUpperCase(Locale.ROOT)
                                + ": " + thesis.group(2).strip() + "\nทบทวนเมื่อ: " + thesis.group(3).strip()
                                + "\nตอบยืนยันเพื่อบันทึกครับ"));
            }
            if (userText.matches("(?isu).*เหตุผลที่ถือ\\s+[A-Z][A-Z0-9.-]{0,7}\\s*[:：].*"))
                return Optional.of(ToolEvidence.finalVerified("investment.manage",
                        "ขอเงื่อนไขที่จะทบทวนการถือด้วยครับ เช่น เหตุผลที่ถือ AMZN: …; ทบทวนเมื่อ: …"));
        }
        if (userText == null || userText.isBlank() || conversationId == null || !isMonitorRequest(userText)) {
            return Optional.empty();
        }
        boolean marketAnalysis = isMarketAnalysis(userText);
        boolean portfolioFact = isPortfolioFactRequest(userText);
        boolean refresh = !portfolioFact && (marketAnalysis
                || userText.matches("(?is).*?(วันนี้|ล่าสุด|latest|today|refresh).*?"));
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
            if (!marketAnalysis && result.value() instanceof Map<?, ?> report
                    && report.get("brief_text") instanceof String brief && !brief.isBlank()) {
                return Optional.of(ToolEvidence.finalVerified(TOOL_NAME, brief));
            }
            return Optional.of(ToolEvidence.verified(TOOL_NAME,
                    (marketAnalysis ? "ข้อมูลพอร์ตของผู้ใช้และตลาดล่าสุดสำหรับวิเคราะห์:\n"
                            : "ข้อมูลแผนลงทุน ข่าว และพอร์ตที่ตรวจสอบได้:\n")
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
        if (com.minikun.investment.InvestmentAdviceIntent.matches(text)
                || com.minikun.investment.InvestmentAdviceIntent.requestsLedgerUpdate(text)) return false;
        String normalized = text.toLowerCase(Locale.ROOT).strip();
        boolean marketAnalysis = isMarketAnalysis(normalized);
        boolean portfolioFact = isPortfolioFactRequest(normalized);
        return (TARGET.matcher(normalized).find() || portfolioFact || marketAnalysis)
                && (ACTION.matcher(normalized).find() || portfolioFact || marketAnalysis)
                && !PRICE_ONLY.matcher(normalized).matches();
    }

    private boolean isMarketAnalysis(String text) {
        return MARKET_ANALYSIS.matcher(text.toLowerCase(Locale.ROOT)).find();
    }

    private boolean isPortfolioFactRequest(String text) {
        String normalized = text.toLowerCase(Locale.ROOT).strip();
        boolean target = PORTFOLIO_TARGET.matcher(normalized).find();
        boolean ownership = PORTFOLIO_OWNERSHIP.matcher(normalized).find();
        return PORTFOLIO_INVENTORY.matcher(normalized).find()
                && (target || ownership)
                && !FRESH_MONITOR_SIGNAL.matcher(normalized).find()
                && !PORTFOLIO_ANALYSIS_SIGNAL.matcher(normalized).find();
    }
}
