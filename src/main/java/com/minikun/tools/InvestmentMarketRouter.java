package com.minikun.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.investment.InvestmentService;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Routes clear price and market-value questions to deterministic external-data tools. */
@Component
@ConditionalOnProperty(name = "minikun.investment.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean({InvestmentDataTool.class, InvestmentService.class})
public final class InvestmentMarketRouter implements ToolRequestRouter {
    private static final String TOOL_NAME = "investment.data";
    private static final Pattern TARGET = Pattern.compile(
            "(?iu)(พอร์ต|หุ้น|การลงทุน|portfolio|holdings?|investment|market|quote|ticker)");
    private static final Pattern ACTION = Pattern.compile(
            "(?iu)(ราคา|มูลค่า|ประเมินมูลค่า|ราคาปัจจุบัน|ราคาล่าสุด|market\s*value|current\s*price|"
                    + "latest\s*price|valuation|quote)");
    private static final Pattern UPPERCASE_SYMBOL = Pattern.compile("\\b[A-Z][A-Z0-9.-]{0,7}\\b");

    private final ToolExecutor executor;
    private final ObjectMapper objectMapper;
    private final InvestmentService investments;

    public InvestmentMarketRouter(ToolExecutor executor, ObjectMapper objectMapper, InvestmentService investments) {
        this.executor = Objects.requireNonNull(executor, "tool executor must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.investments = Objects.requireNonNull(investments, "investment service must not be null");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        return route(userText, conversationId, "default");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId) {
        if (userText == null || userText.isBlank() || conversationId == null || !isMarketRequest(userText, ownerId)) {
            return Optional.empty();
        }
        String action = isPortfolioRequest(userText) ? "portfolio_value" : "quotes";
        Set<String> symbols = portfolioSymbols(userText, ownerId);
        Map<String, Object> arguments = symbols.isEmpty()
                ? Map.of("action", action)
                : Map.of("action", action, "symbols", String.join(",", symbols));
        String callId = "investment-market-" + UUID.randomUUID();
        ToolResult result = executor.execute(new ToolCallContext(conversationId, callId, ownerId),
                new ToolCall(callId, TOOL_NAME, arguments));
        if (!result.success()) {
            return Optional.of(ToolEvidence.finalFailed(TOOL_NAME,
                    "ขออภัยครับ ดึงข้อมูลตลาดลงทุนไม่สำเร็จ: " + result.error()));
        }
        try {
            return Optional.of(ToolEvidence.finalVerified(TOOL_NAME,
                    "ข้อมูลตลาดลงทุนที่ตรวจสอบได้:\n" + objectMapper.writeValueAsString(result.value())));
        } catch (JsonProcessingException exception) {
            return Optional.of(ToolEvidence.finalFailed(TOOL_NAME, "ผลข้อมูลตลาดลงทุนมีรูปแบบไม่ถูกต้องครับ"));
        }
    }

    private boolean isMarketRequest(String text, String ownerId) {
        String normalized = text.toLowerCase(Locale.ROOT);
        return ACTION.matcher(normalized).find()
                && (TARGET.matcher(normalized).find() || !portfolioSymbols(text, ownerId).isEmpty());
    }

    private boolean isPortfolioRequest(String text) {
        return Pattern.compile("(?iu)(พอร์ต|portfolio|holdings?|มูลค่าพอร์ต|market\\s*value)").matcher(text).find();
    }

    private Set<String> portfolioSymbols(String text, String ownerId) {
        Set<String> held = investments.summary(ownerId).positions().stream()
                .map(position -> position.symbol().toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        Set<String> selected = new LinkedHashSet<>();
        var matcher = UPPERCASE_SYMBOL.matcher(text);
        while (matcher.find()) {
            String symbol = matcher.group();
            if (held.contains(symbol)) selected.add(symbol);
        }
        return Set.copyOf(selected);
    }
}
