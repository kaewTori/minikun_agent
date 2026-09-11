package com.minikun.tools;

import com.minikun.investment.InvestmentService;
import com.minikun.investment.InvestmentThesisStatus;
import com.minikun.knowledge.acquisition.AcquiredKnowledgeIndex;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.model.KnowledgeContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
                    + "average-cost summary, review for a long-term portfolio review with active theses and published "
                    + "acquired knowledge, or simulate_buy to test a hypothetical additional cost. Results are not "
                    + "market valuations, do not use live prices, and never submit orders.",
            parameters());

    private final InvestmentService investments;
    private final AcquiredKnowledgeIndex acquiredKnowledge;

    public InvestmentAnalyzeTool(InvestmentService investments) {
        this(investments, null);
    }

    @Autowired
    public InvestmentAnalyzeTool(
            InvestmentService investments, ObjectProvider<AcquiredKnowledgeIndex> acquiredKnowledge) {
        this.investments = Objects.requireNonNull(investments, "investment service must not be null");
        this.acquiredKnowledge = acquiredKnowledge == null ? null : acquiredKnowledge.getIfAvailable();
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
                case "review" -> ToolResult.success(review(context.ownerId()));
                case "simulate_buy" -> ToolResult.success(investments.simulateBuy(
                        context.ownerId(), text(arguments, "symbol"), decimal(arguments, "amount")));
                default -> ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                        "investment analysis action must be portfolio, review, or simulate_buy");
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
                "portfolio, review, or simulate_buy."));
        values.put("symbol", new ToolParameter("symbol", ToolParameterType.STRING, false,
                "Symbol for simulate_buy."));
        values.put("amount", new ToolParameter("amount", ToolParameterType.NUMBER, false,
                "Additional cost in the portfolio base currency for simulate_buy."));
        return Map.copyOf(values);
    }

    private Map<String, Object> review(String ownerId) {
        var portfolio = investments.summary(ownerId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("review_type", "long_term_portfolio");
        result.put("portfolio", portfolio);
        result.put("active_theses", investments.theses(ownerId, InvestmentThesisStatus.ACTIVE));
        result.put("acquired_knowledge", acquiredKnowledge(portfolio, ownerId));
        result.put("knowledge_note", acquiredKnowledge == null
                ? "acquired knowledge index is unavailable"
                : "only published, non-stale acquired claims are included");
        result.put("limitations", List.of(
                "portfolio values use average cost basis",
                "no live market prices or current market value",
                "this tool never submits brokerage orders"));
        if (portfolio.positions().isEmpty()) {
            result.put("setup_required", List.of(
                    "record each holding as an investment transaction before portfolio analysis",
                    "save an investment thesis when the owner's reason for holding is known"));
        }
        return Map.copyOf(result);
    }

    private List<Map<String, Object>> acquiredKnowledge(
            com.minikun.investment.PortfolioSummary portfolio, String ownerId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var position : portfolio.positions()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("symbol", position.symbol());
            if (acquiredKnowledge == null) {
                item.put("status", "unavailable");
                item.put("claims", List.of());
                result.add(Map.copyOf(item));
                continue;
            }
            try {
                KnowledgeContext context = acquiredKnowledge.recall(
                        ownerId, position.symbol(), 3);
                List<Map<String, Object>> claims = context.candidates().stream()
                        .map(this::claim)
                        .toList();
                item.put("status", claims.isEmpty() ? "no_published_claims" : "available");
                item.put("claims", claims);
            } catch (RuntimeException exception) {
                item.put("status", "lookup_failed");
                item.put("claims", List.of());
            }
            result.add(Map.copyOf(item));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> claim(KnowledgeCandidate candidate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", candidate.content());
        result.put("source", candidate.source().name());
        result.put("url", candidate.provenance());
        return Map.copyOf(result);
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
