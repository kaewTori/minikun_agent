package com.minikun.tools;

import com.minikun.investment.InvestmentService;
import com.minikun.investment.InvestmentThesisStatus;
import com.minikun.planner.PlannerConfirmationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Owner-scoped investment ledger, policy, and thesis management. */
@Component
@ConditionalOnProperty(name = "minikun.investment.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean({InvestmentService.class, PlannerConfirmationService.class})
public final class InvestmentManageTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "investment.manage",
            "Manage an owner-scoped investment policy, immutable transaction ledger, and decision-journal theses. "
                    + "Read with get_policy, list_transactions, or list_theses. Writes are set_policy, "
                    + "add_transaction, void_transaction, save_thesis, and close_thesis, and always require explicit "
                    + "confirmation. This tool never fetches market prices or submits brokerage orders.",
            parameters());

    private final InvestmentService investments;
    private final PlannerConfirmationService confirmations;

    public InvestmentManageTool(InvestmentService investments, PlannerConfirmationService confirmations) {
        this.investments = Objects.requireNonNull(investments, "investment service must not be null");
        this.confirmations = Objects.requireNonNull(confirmations, "investment confirmations must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        String action = text(arguments, "action").toLowerCase(java.util.Locale.ROOT);
        boolean confirmed = booleanValue(arguments, "confirmed");
        try {
            return switch (action) {
                case "get_policy" -> ToolResult.success(investments.policy(context.ownerId()));
                case "list_transactions" -> ToolResult.success(Map.of(
                        "owner_id", context.ownerId(),
                        "transactions", investments.transactions(context.ownerId())));
                case "list_theses" -> ToolResult.success(Map.of(
                        "owner_id", context.ownerId(),
                        "theses", investments.theses(context.ownerId(),
                                InvestmentThesisStatus.parse(text(arguments, "status")))));
                case "set_policy", "add_transaction", "void_transaction", "save_thesis", "close_thesis" ->
                    write(context, action, arguments, confirmed);
                default -> ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                        "investment action must be get_policy, set_policy, list_transactions, add_transaction, "
                                + "void_transaction, list_theses, save_thesis, or close_thesis");
            };
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "investment operation is temporarily unavailable");
        }
    }

    private ToolResult write(
            ToolCallContext context, String action, Map<String, Object> arguments, boolean confirmed) {
        if (!confirmed) {
            confirmations.save(context.conversationId(), context.ownerId(), "investment." + action, arguments);
            return ToolResult.success(Map.of(
                    "requires_confirmation", true,
                    "message", confirmationMessage(action),
                    "proposed", proposal(action, arguments)));
        }
        var pending = confirmations.find(context.conversationId(), context.ownerId())
                .filter(value -> value.action().equals("investment." + action))
                .orElseThrow(() -> new IllegalArgumentException(
                        "no matching owner-confirmed investment proposal is pending"));
        Map<String, Object> submitted = new LinkedHashMap<>();
        if (arguments != null) {
            arguments.forEach((key, value) -> {
                if (key != null && !"confirmed".equals(key) && value != null) submitted.put(key, value);
            });
        }
        if (!pending.arguments().equals(submitted)) {
            throw new IllegalArgumentException("confirmed investment arguments do not match the pending proposal");
        }
        Object result = switch (action) {
            case "set_policy" -> investments.setPolicy(
                    context.ownerId(), text(arguments, "base_currency"), nullable(arguments, "benchmark"),
                    decimal(arguments, "max_single_position_percent"));
            case "add_transaction" -> investments.addTransaction(
                    context.ownerId(), context.conversationId().value(), text(arguments, "account"),
                    text(arguments, "type"), text(arguments, "symbol"), text(arguments, "instrument_name"),
                    textOr(arguments, "asset_class", "OTHER"), text(arguments, "currency"),
                    decimal(arguments, "quantity"), decimal(arguments, "unit_price"), decimal(arguments, "amount"),
                    decimal(arguments, "fee"), instant(arguments, "occurred_at"), text(arguments, "note"));
            case "void_transaction" -> Map.of(
                    "voided", investments.voidTransaction(context.ownerId(), uuid(arguments, "transaction_id")),
                    "transaction_id", text(arguments, "transaction_id"));
            case "save_thesis" -> investments.saveThesis(
                    context.ownerId(), context.conversationId().value(), optionalUuid(arguments, "thesis_id"),
                    text(arguments, "symbol"), text(arguments, "summary"), nullable(arguments, "invalidation"),
                    instant(arguments, "next_review_at"));
            case "close_thesis" -> investments.closeThesis(
                    context.ownerId(), uuid(arguments, "thesis_id"));
            default -> throw new IllegalArgumentException("unsupported investment write action");
        };
        confirmations.clear(context.conversationId());
        return ToolResult.success(Map.of("saved", true, "action", action, "result", result));
    }

    private String confirmationMessage(String action) {
        return switch (action) {
            case "set_policy" -> "มินิคุงเตรียมแก้ไขกติกาการลงทุนแล้ว ยืนยันให้บันทึกไหมครับ";
            case "add_transaction" -> "มินิคุงเตรียมเพิ่มธุรกรรมในพอร์ตแล้ว กรุณาตรวจตัวเลขและยืนยันครับ";
            case "void_transaction" -> "มินิคุงเตรียมทำเครื่องหมายยกเลิกธุรกรรมนี้แล้ว ยืนยันไหมครับ";
            case "save_thesis" -> "มินิคุงเตรียมบันทึก thesis การลงทุนแล้ว ยืนยันไหมครับ";
            case "close_thesis" -> "มินิคุงเตรียมปิด thesis นี้แล้ว ยืนยันไหมครับ";
            default -> "ยืนยันการเปลี่ยนแปลงข้อมูลการลงทุนไหมครับ";
        };
    }

    private Map<String, Object> proposal(String action, Map<String, Object> arguments) {
        Map<String, Object> proposed = new LinkedHashMap<>();
        proposed.put("action", action);
        java.util.List<String> keys = switch (action) {
            case "set_policy" -> java.util.List.of(
                    "base_currency", "benchmark", "max_single_position_percent");
            case "add_transaction" -> java.util.List.of(
                    "account", "type", "symbol", "currency", "quantity", "unit_price", "amount", "fee",
                    "occurred_at");
            case "void_transaction" -> java.util.List.of("transaction_id");
            case "save_thesis" -> java.util.List.of(
                    "thesis_id", "symbol", "summary", "invalidation", "next_review_at");
            case "close_thesis" -> java.util.List.of("thesis_id");
            default -> java.util.List.of();
        };
        for (String key : keys) {
            Object value = arguments == null ? null : arguments.get(key);
            if (value != null && !value.toString().isBlank()) proposed.put(key, value);
        }
        return Map.copyOf(proposed);
    }

    private static Map<String, ToolParameter> parameters() {
        Map<String, ToolParameter> values = new LinkedHashMap<>();
        values.put("action", parameter("action", ToolParameterType.STRING, true,
                "get_policy, set_policy, list_transactions, add_transaction, void_transaction, list_theses, "
                        + "save_thesis, or close_thesis."));
        values.put("base_currency", parameter("base_currency", ToolParameterType.STRING, false,
                "Three-letter portfolio base currency, such as THB."));
        values.put("benchmark", parameter("benchmark", ToolParameterType.STRING, false,
                "Optional benchmark identifier chosen by the owner."));
        values.put("max_single_position_percent", parameter("max_single_position_percent", ToolParameterType.NUMBER,
                false, "Maximum cost-basis allocation percentage for one symbol."));
        values.put("account", parameter("account", ToolParameterType.STRING, false,
                "Investment account name; required for add_transaction."));
        values.put("type", parameter("type", ToolParameterType.STRING, false,
                "BUY, SELL, DIVIDEND, FEE, CASH_DEPOSIT, or CASH_WITHDRAWAL."));
        values.put("symbol", parameter("symbol", ToolParameterType.STRING, false,
                "Instrument ticker or identifier."));
        values.put("instrument_name", parameter("instrument_name", ToolParameterType.STRING, false,
                "Human-readable instrument name."));
        values.put("asset_class", parameter("asset_class", ToolParameterType.STRING, false,
                "Asset class such as EQUITY, ETF, BOND, FUND, or CRYPTO."));
        values.put("currency", parameter("currency", ToolParameterType.STRING, false,
                "Transaction currency; phase one requires the base currency."));
        values.put("quantity", parameter("quantity", ToolParameterType.NUMBER, false,
                "Positive quantity for BUY or SELL."));
        values.put("unit_price", parameter("unit_price", ToolParameterType.NUMBER, false,
                "Non-negative unit price for BUY or SELL."));
        values.put("amount", parameter("amount", ToolParameterType.NUMBER, false,
                "Positive cash amount for non-trade transaction types."));
        values.put("fee", parameter("fee", ToolParameterType.NUMBER, false,
                "Non-negative trade fee; defaults to zero."));
        values.put("occurred_at", parameter("occurred_at", ToolParameterType.STRING, false,
                "ISO-8601 transaction time; defaults to now."));
        values.put("note", parameter("note", ToolParameterType.STRING, false, "Optional transaction note."));
        values.put("transaction_id", parameter("transaction_id", ToolParameterType.STRING, false,
                "Transaction UUID for void_transaction."));
        values.put("thesis_id", parameter("thesis_id", ToolParameterType.STRING, false,
                "Thesis UUID for update or close."));
        values.put("summary", parameter("summary", ToolParameterType.STRING, false,
                "Investment thesis and reasons for following the instrument."));
        values.put("invalidation", parameter("invalidation", ToolParameterType.STRING, false,
                "Observable conditions that would invalidate the thesis."));
        values.put("next_review_at", parameter("next_review_at", ToolParameterType.STRING, false,
                "Optional ISO-8601 time to review the thesis."));
        values.put("status", parameter("status", ToolParameterType.STRING, false,
                "Optional ACTIVE or CLOSED filter for list_theses."));
        values.put("confirmed", parameter("confirmed", ToolParameterType.BOOLEAN, false,
                "Must be true to apply a write after the owner confirms."));
        return Map.copyOf(values);
    }

    private static ToolParameter parameter(
            String name, ToolParameterType type, boolean required, String description) {
        return new ToolParameter(name, type, required, description);
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private String nullable(Map<String, Object> arguments, String key) {
        String value = text(arguments, key);
        return value.isBlank() ? null : value;
    }

    private String textOr(Map<String, Object> arguments, String key, String fallback) {
        String value = text(arguments, key);
        return value.isBlank() ? fallback : value;
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

    private Instant instant(Map<String, Object> arguments, String key) {
        String value = text(arguments, key);
        if (value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(key + " must be an ISO-8601 instant");
        }
    }

    private UUID uuid(Map<String, Object> arguments, String key) {
        UUID value = optionalUuid(arguments, key);
        if (value == null) throw new IllegalArgumentException(key + " must be a valid UUID");
        return value;
    }

    private UUID optionalUuid(Map<String, Object> arguments, String key) {
        String value = text(arguments, key);
        if (value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(key + " must be a valid UUID");
        }
    }

    private boolean booleanValue(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value instanceof Boolean bool ? bool : value != null && Boolean.parseBoolean(value.toString());
    }
}
