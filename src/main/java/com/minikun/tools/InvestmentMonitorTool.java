package com.minikun.tools;

import com.minikun.investment.InvestmentMonitoringService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Read-only professional investment companion: plan, daily brief, and news history. */
@Component
@ConditionalOnProperty(name = "minikun.investment.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(InvestmentMonitoringService.class)
public final class InvestmentMonitorTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "investment.monitor",
            "Act as a read-only professional investment companion. Use plan for the owner's saved mandate and thesis, "
                    + "daily_brief for the current portfolio plus fresh market/news evidence, or news for recent stored "
                    + "news. Explain facts, assumptions, risks, counterarguments, thesis invalidation, and uncertainty. "
                    + "Use brief_text and news.events.what_happened as the reviewed Thai summaries shared with reminders; "
                    + "do not replace them with raw links. ETF market-context news is not proof of its holdings or returns. "
                    + "When missing_thesis_symbols is non-empty, ask for the owner's reason for holding one symbol "
                    + "and its review condition; save only their actual answers using investment.manage save_thesis. "
                    + "Recommendations are conditional research guidance only; never place live orders or present a "
                    + "backtest as a guarantee.",
            parameters());

    private final InvestmentMonitoringService monitoring;

    public InvestmentMonitorTool(InvestmentMonitoringService monitoring) {
        this.monitoring = Objects.requireNonNull(monitoring, "investment monitoring service must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public boolean requiresExplicitConfirmation(Map<String, Object> arguments) {
        return false;
    }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        String action = text(arguments, "action").toLowerCase(Locale.ROOT);
        try {
            return switch (action) {
                case "plan" -> ToolResult.success(monitoring.plan(context.ownerId()));
                case "daily_brief", "refresh" -> ToolResult.success(
                        monitoring.dailyBrief(context.ownerId(), "refresh".equals(action) || booleanValue(arguments, "refresh")));
                case "news" -> ToolResult.success(Map.of(
                        "owner_id", context.ownerId(),
                        "reviewed_brief", monitoring.dailyBrief(context.ownerId(), false),
                        "news", monitoring.recentNews(context.ownerId(),
                                integer(arguments, "lookback_hours", 48), integer(arguments, "limit", 20))));
                default -> ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                        "investment monitor action must be plan, daily_brief, refresh, or news");
            };
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "investment monitoring is temporarily unavailable");
        }
    }

    private static Map<String, ToolParameter> parameters() {
        Map<String, ToolParameter> values = new LinkedHashMap<>();
        values.put("action", new ToolParameter("action", ToolParameterType.STRING, true,
                "plan, daily_brief, refresh, or news."));
        values.put("refresh", new ToolParameter("refresh", ToolParameterType.BOOLEAN, false,
                "Refresh prices and news instead of using the latest stored brief."));
        values.put("lookback_hours", new ToolParameter("lookback_hours", ToolParameterType.INTEGER, false,
                "News lookback window from 1 to 720 hours; default 48."));
        values.put("limit", new ToolParameter("limit", ToolParameterType.INTEGER, false,
                "Maximum news items from 1 to 100; default 20."));
        return Map.copyOf(values);
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().strip();
    }

    private boolean booleanValue(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value instanceof Boolean bool && bool;
    }

    private int integer(Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) return fallback;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }
}
