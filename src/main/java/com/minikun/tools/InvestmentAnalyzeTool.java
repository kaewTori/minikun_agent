package com.minikun.tools;

import com.minikun.investment.InvestmentService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Read-only deterministic cost-basis portfolio analysis and simulations. */
@Component
@ConditionalOnProperty(name = "minikun.investment.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(InvestmentService.class)
public final class InvestmentAnalyzeTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "investment.analyze",
            "Analyze the authenticated owner's investment ledger deterministically. Use portfolio for the current "
                    + "average-cost summary or simulate_buy to test a hypothetical additional cost. Results are not "
                    + "market valuations, do not use live prices, and never submit orders.",
            parameters());

    private final InvestmentService investments;

    public InvestmentAnalyzeTool(InvestmentService investments) {
        this.investments = Objects.requireNonNull(investments, "investment service must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override public boolean requiresExplicitConfirmation(Map<String, Object> arguments) { return false; }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        String action = text(arguments, "action").toLowerCase(java.util.Locale.ROOT);
        try {
            return switch (action) {
                case "portfolio" -> ToolResult.success(investments.summary(context.ownerId()));
                case "simulate_buy" -> ToolResult.success(investments.simulateBuy(
                        context.ownerId(), text(arguments, "symbol"), decimal(arguments, "amount")));
                default -> ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                        "investment analysis action must be portfolio or simulate_buy");
            };
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "investment analysis is temporarily unavailable");
        }
    }

    private static Map<String, ToolParameter> parameters() {
        Map<String, ToolParameter> values = new LinkedHashMap<>();
        values.put("action", new ToolParameter("action", ToolParameterType.STRING, true,
                "portfolio or simulate_buy."));
        values.put("symbol", new ToolParameter("symbol", ToolParameterType.STRING, false,
                "Symbol for simulate_buy."));
        values.put("amount", new ToolParameter("amount", ToolParameterType.NUMBER, false,
                "Additional cost in the portfolio base currency for simulate_buy."));
        return Map.copyOf(values);
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private BigDecimal decimal(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null || value.toString().isBlank()) return null;
        try {
            return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be a valid decimal number");
        }
    }
}
