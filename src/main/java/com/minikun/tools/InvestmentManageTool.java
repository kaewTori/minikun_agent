package com.minikun.tools;

import com.minikun.investment.InvestmentService;
import com.minikun.investment.InvestmentThesisStatus;
import com.minikun.investment.InvestmentQuotePriority;
import com.minikun.investment.InvestmentTransactionType;
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
            "Manage an owner-scoped investment policy (goal, time horizon, risk tolerance), immutable transaction ledger, and decision-journal theses. "
                    + "Read with get_policy, list_transactions, or list_theses. Writes are set_policy, "
                    + "set_quote_priority, add_transaction, void_transaction, save_thesis, and close_thesis, and always require explicit "
                    + "confirmation. Use add_transaction to record exactly one completed past BUY or SELL; it updates the ledger only and never "
                    + "submits a brokerage order. If multiple trades are reported, handle them one at a time and never claim an unlisted symbol was saved. "
                    + "For add_transaction, collect the type, symbol, quantity, and unit price before calling the tool; use an existing account only when exactly one is known. "
                    + "Ask for missing trade details instead of guessing. This tool never fetches market prices.",
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
    public boolean requiresExplicitConfirmation(Map<String, Object> arguments) {
        return switch (text(arguments, "action").toLowerCase(java.util.Locale.ROOT)) {
            case "set_policy", "set_quote_priority", "add_transaction", "void_transaction", "save_thesis", "close_thesis" -> true;
            default -> false;
        };
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
                case "set_policy", "set_quote_priority", "add_transaction", "void_transaction", "save_thesis", "close_thesis" ->
                    write(context, action, arguments, confirmed);
                default -> ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                        "investment action must be get_policy, set_policy, list_transactions, add_transaction, "
                                + "void_transaction, list_theses, set_quote_priority, save_thesis, or close_thesis");
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
        Map<String, Object> normalized = normalizeArguments(context, action, arguments);
        validateWriteArguments(action, normalized);
        if (!confirmed) {
            confirmations.save(context.conversationId(), context.ownerId(), "investment." + action, normalized);
            return ToolResult.success(Map.of(
                    "requires_confirmation", true,
                    "message", confirmationMessage(action),
                    "proposed", proposal(action, normalized)));
        }
        var pending = confirmations.find(context.conversationId(), context.ownerId())
                .filter(value -> value.action().equals("investment." + action))
                .orElseThrow(() -> new IllegalArgumentException(
                        "no matching owner-confirmed investment proposal is pending"));
        Map<String, Object> expected = new LinkedHashMap<>(pending.arguments());
        Object pendingAccount = expected.get("account");
        if ((!expected.containsKey("account") || pendingAccount == null || pendingAccount.toString().isBlank())
                && normalized.containsKey("account")) {
            expected.put("account", normalized.get("account"));
        }
        if (!expected.equals(normalized)) {
            throw new IllegalArgumentException("confirmed investment arguments do not match the pending proposal");
        }
        Object result = switch (action) {
            case "set_policy" -> investments.setPolicy(
                    context.ownerId(), text(normalized, "base_currency"), nullable(normalized, "benchmark"),
                    decimal(normalized, "max_single_position_percent"), nullable(normalized, "goal"),
                    nullable(normalized, "time_horizon"), nullable(normalized, "risk_tolerance"));
            case "set_quote_priority" -> investments.setQuotePriorities(
                    context.ownerId(), symbols(normalized), InvestmentQuotePriority.parse(text(normalized, "priority")));
            case "add_transaction" -> investments.addTransaction(
                    context.ownerId(), context.conversationId().value(), text(normalized, "account"),
                    text(normalized, "type"), text(normalized, "symbol"), text(normalized, "instrument_name"),
                    textOr(normalized, "asset_class", "OTHER"), text(normalized, "currency"),
                    decimal(normalized, "quantity"), decimal(normalized, "unit_price"), decimal(normalized, "amount"),
                    transactionFee(normalized), instant(normalized, "occurred_at"), text(normalized, "note"));
            case "void_transaction" -> Map.of(
                    "voided", investments.voidTransaction(context.ownerId(), uuid(normalized, "transaction_id")),
                    "transaction_id", text(normalized, "transaction_id"));
            case "save_thesis" -> investments.saveThesis(
                    context.ownerId(), context.conversationId().value(), optionalUuid(normalized, "thesis_id"),
                    text(normalized, "symbol"), text(normalized, "summary"), nullable(normalized, "invalidation"),
                    instant(normalized, "next_review_at"));
            case "close_thesis" -> investments.closeThesis(
                    context.ownerId(), uuid(normalized, "thesis_id"));
            default -> throw new IllegalArgumentException("unsupported investment write action");
        };
        confirmations.clear(context.conversationId());
        return ToolResult.success(Map.of("saved", true, "action", action, "result", result));
    }

    private Map<String, Object> normalizeArguments(
            ToolCallContext context, String action, Map<String, Object> arguments) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (arguments != null) {
            arguments.forEach((key, value) -> {
                if (key != null && !"confirmed".equals(key) && value != null) normalized.put(key, value);
            });
        }
        if ("add_transaction".equals(action) && text(normalized, "account").isBlank()) {
            String account = investments.transactions(context.ownerId()).stream()
                    .map(value -> value.account()).filter(value -> !value.isBlank()).distinct().toList()
                    .stream().reduce((first, second) -> "").orElse("");
            if (!account.isBlank()) normalized.put("account", account);
        }
        return normalized;
    }

    private void validateWriteArguments(String action, Map<String, Object> arguments) {
        if (!"add_transaction".equals(action)) return;
        if (text(arguments, "account").isBlank()) {
            throw new IllegalArgumentException("account is required for add_transaction");
        }
        InvestmentTransactionType type = InvestmentTransactionType.parse(text(arguments, "type"));
        if (type.securityTrade()) {
            requireText(arguments, "symbol");
            requirePositive(arguments, "quantity");
            requireNonNegative(arguments, "unit_price");
            BigDecimal net = decimal(arguments, "net_proceeds");
            if (net != null && net.signum() < 0) {
                throw new IllegalArgumentException("net_proceeds must not be negative");
            }
            BigDecimal fee = decimal(arguments, "fee");
            if (fee != null && fee.signum() < 0) {
                throw new IllegalArgumentException("fee must not be negative");
            }
            if (net != null && type != InvestmentTransactionType.SELL) {
                throw new IllegalArgumentException("net_proceeds is only valid for SELL transactions");
            }
            return;
        }
        requirePositive(arguments, "amount");
        if (type == InvestmentTransactionType.DIVIDEND) requireText(arguments, "symbol");
    }

    private BigDecimal transactionFee(Map<String, Object> arguments) {
        BigDecimal fee = decimal(arguments, "fee");
        BigDecimal net = decimal(arguments, "net_proceeds");
        if (net == null) return fee;
        BigDecimal gross = decimal(arguments, "quantity").multiply(decimal(arguments, "unit_price"));
        BigDecimal derived = gross.subtract(net);
        if (derived.signum() < 0) {
            throw new IllegalArgumentException("net_proceeds cannot exceed quantity multiplied by unit_price");
        }
        if (fee != null && fee.compareTo(derived) != 0) {
            throw new IllegalArgumentException("fee and net_proceeds do not describe the same transaction");
        }
        return derived;
    }

    private void requireText(Map<String, Object> arguments, String key) {
        if (text(arguments, key).isBlank()) throw new IllegalArgumentException(key + " is required for add_transaction");
    }

    private void requirePositive(Map<String, Object> arguments, String key) {
        BigDecimal value = decimal(arguments, key);
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(key + " must be greater than zero");
        }
    }

    private void requireNonNegative(Map<String, Object> arguments, String key) {
        BigDecimal value = decimal(arguments, key);
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(key + " must not be negative");
        }
    }

    private String confirmationMessage(String action) {
        return switch (action) {
            case "set_policy" -> "มินิคุงเตรียมแก้ไขกติกาการลงทุนแล้ว ยืนยันให้บันทึกไหมครับ";
            case "set_quote_priority" -> "มินิคุงเตรียมเปลี่ยน priority การติดตามราคาของหุ้นแล้ว ยืนยันให้บันทึกไหมครับ";
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
                    "base_currency", "benchmark", "max_single_position_percent", "goal", "time_horizon",
                    "risk_tolerance");
            case "set_quote_priority" -> java.util.List.of("symbols", "priority");
            case "add_transaction" -> java.util.List.of(
                    "account", "type", "symbol", "currency", "quantity", "unit_price", "amount", "fee",
                    "net_proceeds", "occurred_at");
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
                "get_policy, set_policy, list_transactions, set_quote_priority, add_transaction, "
                        + "void_transaction, list_theses, save_thesis, or close_thesis."));
        values.put("base_currency", parameter("base_currency", ToolParameterType.STRING, false,
                "Three-letter portfolio base currency, such as THB."));
        values.put("benchmark", parameter("benchmark", ToolParameterType.STRING, false,
                "Optional benchmark identifier chosen by the owner."));
        values.put("max_single_position_percent", parameter("max_single_position_percent", ToolParameterType.NUMBER,
                false, "Maximum cost-basis allocation percentage for one symbol."));
        values.put("goal", parameter("goal", ToolParameterType.STRING, false,
                "Investment goal, for example long-term wealth or a home deposit."));
        values.put("time_horizon", parameter("time_horizon", ToolParameterType.STRING, false,
                "Investment time horizon, for example 10 years or retirement."));
        values.put("risk_tolerance", parameter("risk_tolerance", ToolParameterType.STRING, false,
                "Owner-described risk tolerance, for example low, moderate, or high."));
        values.put("account", parameter("account", ToolParameterType.STRING, false,
                "Investment account name; required for add_transaction."));
        values.put("type", parameter("type", ToolParameterType.STRING, false,
                "BUY, SELL, DIVIDEND, FEE, CASH_DEPOSIT, or CASH_WITHDRAWAL."));
        values.put("symbol", parameter("symbol", ToolParameterType.STRING, false,
                "Instrument ticker or identifier."));
        values.put("symbols", parameter("symbols", ToolParameterType.STRING, false,
                "Comma-separated held symbols whose quote refresh priority should change."));
        values.put("priority", parameter("priority", ToolParameterType.STRING, false,
                "Quote refresh priority: HIGH, NORMAL, or MINOR."));
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
        values.put("net_proceeds", parameter("net_proceeds", ToolParameterType.NUMBER, false,
                "Net cash received for a SELL; the tool derives the fee from quantity times unit price minus this amount."));
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

    private java.util.List<String> symbols(Map<String, Object> arguments) {
        String value = text(arguments, "symbols");
        if (value.isBlank()) value = text(arguments, "symbol");
        return java.util.Arrays.stream(value.split(","))
                .map(String::strip).filter(item -> !item.isBlank()).distinct().toList();
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
