package com.minikun.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.investment.QuantDingerClient;
import com.minikun.planner.PendingPlannerConfirmation;
import com.minikun.planner.PlannerConfirmationService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Read/backtest-only QuantDinger integration; no broker-order capability is exposed. */
@Component
@ConditionalOnProperty(name = "minikun.investment.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean({QuantDingerClient.class, PlannerConfirmationService.class})
public final class QuantDingerTool implements Tool {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "investment.quantdinger",
            "Use the optional QuantDinger sidecar for market data, OHLCV, strategy compilation/version history, "
                    + "read-only runtime status, and asynchronous backtests. It is configured for R/B capabilities "
                    + "only and never sends live or paper broker orders from Mini-kun.",
            parameters());

    private final QuantDingerClient client;
    private final PlannerConfirmationService confirmations;
    private final ObjectMapper objectMapper;

    public QuantDingerTool(
            QuantDingerClient client, PlannerConfirmationService confirmations, ObjectMapper objectMapper) {
        this.client = Objects.requireNonNull(client, "QuantDinger client must not be null");
        this.confirmations = Objects.requireNonNull(confirmations, "confirmation service must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public boolean requiresExplicitConfirmation(Map<String, Object> arguments) {
        return "save_strategy".equalsIgnoreCase(text(arguments, "action"));
    }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        String action = text(arguments, "action").toLowerCase(java.util.Locale.ROOT);
        try {
            if (!client.configured()) return ToolResult.success(setup(action));
            return switch (action) {
                case "status" -> ToolResult.success(status());
                case "markets" -> ToolResult.success(client.markets());
                case "price" -> ToolResult.success(client.price(text(arguments, "market"), text(arguments, "symbol")));
                case "klines" -> ToolResult.success(client.klines(text(arguments, "market"), text(arguments, "symbol"),
                        textOr(arguments, "timeframe", "1D"), integer(arguments, "limit", 300)));
                case "compile_strategy" -> ToolResult.success(client.compileStrategy(
                        nullable(arguments, "code"), integerOrNull(arguments, "source_id")));
                case "strategy_versions" -> ToolResult.success(
                        client.strategyVersions(integer(arguments, "source_id", 0)));
                case "save_strategy" -> saveStrategy(context, arguments);
                case "backtest" -> ToolResult.success(client.submitBacktest(
                        backtestRequest(arguments), "minikun-backtest-" + context.callId()));
                case "job" -> ToolResult.success(client.job(text(arguments, "job_id")));
                default -> ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                        "QuantDinger action must be status, markets, price, klines, compile_strategy, "
                                + "save_strategy, strategy_versions, backtest, or job");
            };
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "QuantDinger is temporarily unavailable");
        }
    }

    private Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", true);
        result.put("health", client.health());
        result.put("runtime", client.runtimeOverview());
        result.put("live_orders_exposed", false);
        return Map.copyOf(result);
    }

    private ToolResult saveStrategy(ToolCallContext context, Map<String, Object> arguments) {
        boolean confirmed = booleanValue(arguments, "confirmed");
        if (!confirmed) {
            confirmations.save(context.conversationId(), context.ownerId(), "quantdinger.save_strategy", arguments);
            return ToolResult.success(Map.of(
                    "requires_confirmation", true,
                    "message", "มินิคุงเตรียมบันทึก Strategy version ใน QuantDinger แล้ว ยืนยันไหมครับ",
                    "proposed", proposal(arguments)));
        }
        PendingPlannerConfirmation pending = confirmations.find(context.conversationId(), context.ownerId())
                .filter(value -> value.action().equals("quantdinger.save_strategy"))
                .orElseThrow(() -> new IllegalArgumentException("no matching strategy proposal is pending"));
        Map<String, Object> submitted = withoutConfirmed(arguments);
        if (!pending.arguments().equals(submitted)) {
            throw new IllegalArgumentException("confirmed strategy arguments do not match the pending proposal");
        }
        Map<String, Object> result = client.saveStrategySource(strategyRequest(arguments),
                "minikun-strategy-" + context.callId());
        confirmations.clear(context.conversationId());
        return ToolResult.success(Map.of("saved", true, "versioned", true, "result", result));
    }

    private Map<String, Object> strategyRequest(Map<String, Object> arguments) {
        Map<String, Object> request = new LinkedHashMap<>();
        copy(arguments, request, "name", "code", "description", "template_key", "param_schema", "metadata");
        for (String jsonKey : List.of("param_schema_json", "metadata_json")) {
            String value = text(arguments, jsonKey);
            if (!value.isBlank()) {
                String target = jsonKey.replace("_json", "");
                request.put(target, json(value, jsonKey));
            }
        }
        return request;
    }

    private Map<String, Object> backtestRequest(Map<String, Object> arguments) {
        Map<String, Object> request = new LinkedHashMap<>();
        copy(arguments, request, "code", "startDate", "endDate", "initialCapital", "commission", "slippage",
                "leverageEnabled", "leverage");
        rename(arguments, request, "start_date", "startDate");
        rename(arguments, request, "end_date", "endDate");
        rename(arguments, request, "initial_capital", "initialCapital");
        rename(arguments, request, "leverage_enabled", "leverageEnabled");
        String params = text(arguments, "params_json");
        if (!params.isBlank()) request.put("params", json(params, "params_json"));
        return request;
    }

    private Map<String, Object> proposal(Map<String, Object> arguments) {
        Map<String, Object> result = new LinkedHashMap<>();
        copy(arguments, result, "name", "description", "template_key", "param_schema_json", "metadata_json");
        if (!text(arguments, "code").isBlank()) result.put("code", "<strategy source provided>");
        return Map.copyOf(result);
    }

    private Map<String, Object> setup(String action) {
        return Map.of("status", "not_configured", "action", action,
                "setup_required", List.of(
                        "set MINIKUN_INVESTMENT_QUANTDINGER_ENABLED=true",
                        "set MINIKUN_INVESTMENT_QUANTDINGER_AGENT_TOKEN with R/B scopes"));
    }

    private Map<String, Object> withoutConfirmed(Map<String, Object> arguments) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (arguments != null) arguments.forEach((key, value) -> {
            if (key != null && !"confirmed".equals(key) && value != null) result.put(key, value);
        });
        return result;
    }

    private void copy(Map<String, Object> from, Map<String, Object> to, String... keys) {
        if (from == null) return;
        for (String key : keys) {
            Object value = from.get(key);
            if (value != null && !value.toString().isBlank()) to.put(key, value);
        }
    }

    private void rename(Map<String, Object> from, Map<String, Object> to, String fromKey, String toKey) {
        Object value = from == null ? null : from.get(fromKey);
        if (value != null && !value.toString().isBlank()) to.put(toKey, value);
    }

    private Map<String, Object> json(String value, String field) {
        try {
            Map<String, Object> result = objectMapper.readValue(value, MAP);
            return result == null ? Map.of() : result;
        } catch (Exception exception) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().strip();
    }

    private String nullable(Map<String, Object> arguments, String key) {
        String value = text(arguments, key);
        return value.isBlank() ? null : value;
    }

    private String textOr(Map<String, Object> arguments, String key, String fallback) {
        String value = text(arguments, key);
        return value.isBlank() ? fallback : value;
    }

    private int integer(Map<String, Object> arguments, String key, int fallback) {
        Integer value = integerOrNull(arguments, key);
        return value == null ? fallback : value;
    }

    private Integer integerOrNull(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null || value.toString().isBlank()) return null;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private boolean booleanValue(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value instanceof Boolean bool && bool;
    }

    private static Map<String, ToolParameter> parameters() {
        Map<String, ToolParameter> values = new LinkedHashMap<>();
        values.put("action", new ToolParameter("action", ToolParameterType.STRING, true,
                "status, markets, price, klines, compile_strategy, save_strategy, strategy_versions, backtest, or job."));
        values.put("market", new ToolParameter("market", ToolParameterType.STRING, false, "QuantDinger market identifier."));
        values.put("symbol", new ToolParameter("symbol", ToolParameterType.STRING, false, "Market symbol."));
        values.put("timeframe", new ToolParameter("timeframe", ToolParameterType.STRING, false, "OHLCV timeframe, default 1D."));
        values.put("limit", new ToolParameter("limit", ToolParameterType.INTEGER, false, "OHLCV limit from 1 to 2000."));
        values.put("code", new ToolParameter("code", ToolParameterType.STRING, false, "Strategy API V2 Python source."));
        values.put("source_id", new ToolParameter("source_id", ToolParameterType.INTEGER, false, "QuantDinger strategy source id."));
        values.put("name", new ToolParameter("name", ToolParameterType.STRING, false, "Strategy source name."));
        values.put("description", new ToolParameter("description", ToolParameterType.STRING, false, "Strategy description."));
        values.put("template_key", new ToolParameter("template_key", ToolParameterType.STRING, false, "Optional strategy template key."));
        values.put("param_schema_json", new ToolParameter("param_schema_json", ToolParameterType.STRING, false, "Parameter schema as a JSON object."));
        values.put("metadata_json", new ToolParameter("metadata_json", ToolParameterType.STRING, false, "Strategy metadata as a JSON object."));
        values.put("start_date", new ToolParameter("start_date", ToolParameterType.STRING, false, "Backtest start date, YYYY-MM-DD."));
        values.put("end_date", new ToolParameter("end_date", ToolParameterType.STRING, false, "Backtest end date, YYYY-MM-DD."));
        values.put("initial_capital", new ToolParameter("initial_capital", ToolParameterType.NUMBER, false, "Positive backtest capital."));
        values.put("commission", new ToolParameter("commission", ToolParameterType.NUMBER, false, "Backtest commission rate."));
        values.put("slippage", new ToolParameter("slippage", ToolParameterType.NUMBER, false, "Optional backtest slippage rate."));
        values.put("leverage_enabled", new ToolParameter("leverage_enabled", ToolParameterType.BOOLEAN, false, "Keep false for conservative research."));
        values.put("leverage", new ToolParameter("leverage", ToolParameterType.NUMBER, false, "Backtest leverage; default 1."));
        values.put("params_json", new ToolParameter("params_json", ToolParameterType.STRING, false, "Backtest parameters as a JSON object."));
        values.put("job_id", new ToolParameter("job_id", ToolParameterType.STRING, false, "QuantDinger job id."));
        values.put("confirmed", new ToolParameter("confirmed", ToolParameterType.BOOLEAN, false, "Explicitly confirm saving a strategy source."));
        return Map.copyOf(values);
    }
}
