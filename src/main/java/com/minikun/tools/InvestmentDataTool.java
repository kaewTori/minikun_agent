package com.minikun.tools;

import com.minikun.investment.InvestmentExternalDataService;
import com.minikun.investment.InvestmentService;
import com.minikun.planner.PendingPlannerConfirmation;
import com.minikun.planner.PlannerConfirmationService;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Read-only market, FX, SEC, and paper-broker data, with confirmation for paper orders. */
@Component
@ConditionalOnProperty(name = "minikun.investment.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean({InvestmentService.class, InvestmentExternalDataService.class, PlannerConfirmationService.class})
public final class InvestmentDataTool implements Tool {
    private static final MathContext MATH = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "investment.data",
            "Read external investment data. Use quotes for current Twelve Data prices, portfolio_value for a "
                    + "latest-quote valuation, fx for a reference exchange rate and optional budget conversion "
                    + "(amount in base_currency multiplied by rate to quote_currency), sec_filings for official EDGAR "
                    + "filings, paper_account for Alpaca paper status, or paper_order to submit a paper-only order "
                    + "after explicit confirmation. Never treats a quote as a guarantee or submits a live order.",
            parameters());

    private final InvestmentService investments;
    private final InvestmentExternalDataService external;
    private final PlannerConfirmationService confirmations;

    public InvestmentDataTool(
            InvestmentService investments,
            InvestmentExternalDataService external,
            PlannerConfirmationService confirmations) {
        this.investments = Objects.requireNonNull(investments, "investment service must not be null");
        this.external = Objects.requireNonNull(external, "external investment data must not be null");
        this.confirmations = Objects.requireNonNull(confirmations, "investment confirmations must not be null");
    }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override
    public boolean requiresExplicitConfirmation(Map<String, Object> arguments) {
        return "paper_order".equalsIgnoreCase(text(arguments, "action"));
    }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        String action = text(arguments, "action").toLowerCase(Locale.ROOT);
        try {
            return switch (action) {
                case "quotes" -> ToolResult.success(quotes(arguments, context.ownerId()));
                case "portfolio_value" -> ToolResult.success(portfolioValue(context.ownerId()));
                case "fx" -> ToolResult.success(fx(arguments, context.ownerId()));
                case "sec_filings" -> ToolResult.success(secFilings(arguments));
                case "paper_account" -> ToolResult.success(paperAccount());
                case "paper_order" -> paperOrder(context, arguments, booleanValue(arguments, "confirmed"));
                default -> ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                        "investment data action must be quotes, portfolio_value, fx, sec_filings, paper_account, or paper_order");
            };
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "external investment data is temporarily unavailable");
        }
    }

    private Map<String, Object> quotes(Map<String, Object> arguments, String ownerId) {
        List<String> symbols = symbols(arguments.get("symbols"));
        if (symbols.isEmpty()) symbols = symbols(arguments.get("symbol"));
        if (symbols.isEmpty()) symbols = portfolioSymbols(ownerId);
        if (!external.marketDataConfigured()) return setup("quotes", "MINIKUN_INVESTMENT_TWELVE_DATA_API_KEY");
        Map<String, InvestmentExternalDataService.MarketQuote> values = external.latestQuotes(symbols);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String symbol : symbols) {
            InvestmentExternalDataService.MarketQuote quote = values.get(symbol);
            if (quote == null) {
                missing.add(symbol);
                continue;
            }
            rows.add(Map.of("symbol", quote.symbol(), "price", quote.price(), "currency", quote.currency(),
                    "observed_at", quote.observedAt(), "source", quote.source()));
        }
        return Map.of("status", missing.isEmpty() ? "ok" : "partial", "quotes", rows, "missing_symbols", missing,
                "source", "twelve-data", "as_of", Instant.now());
    }

    private Map<String, Object> portfolioValue(String ownerId) {
        var portfolio = investments.summary(ownerId);
        if (portfolio.positions().isEmpty()) {
            return Map.of("status", "empty", "positions", List.of(), "setup_required",
                    List.of("record holdings before requesting market valuation"));
        }
        if (!external.marketDataConfigured()) return setup("portfolio_value", "MINIKUN_INVESTMENT_TWELVE_DATA_API_KEY");
        List<String> symbols = portfolio.positions().stream().map(position -> position.symbol()).toList();
        Map<String, InvestmentExternalDataService.MarketQuote> quotes = external.latestQuotes(symbols);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        BigDecimal totalMarket = BigDecimal.ZERO;
        for (var position : portfolio.positions()) {
            InvestmentExternalDataService.MarketQuote quote = quotes.get(position.symbol());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", position.symbol());
            row.put("quantity", position.quantity());
            row.put("cost_basis", position.costBasis());
            row.put("average_cost", position.averageCost());
            if (quote == null) {
                missing.add(position.symbol());
                row.put("status", "quote_missing");
            } else if (!portfolio.baseCurrency().equalsIgnoreCase(quote.currency())) {
                missing.add(position.symbol());
                row.put("status", "currency_mismatch");
                row.put("quote_currency", quote.currency());
            } else {
                BigDecimal marketValue = quote.price().multiply(position.quantity(), MATH);
                totalMarket = totalMarket.add(marketValue, MATH);
                row.put("status", "ok");
                row.put("market_price", quote.price());
                row.put("market_value", marketValue);
                row.put("unrealized_profit_loss", marketValue.subtract(position.costBasis(), MATH));
                row.put("quote_observed_at", quote.observedAt());
                row.put("quote_source", quote.source());
            }
            rows.add(row);
        }
        BigDecimal finalTotalMarket = totalMarket;
        rows.forEach(row -> {
            Object value = row.get("market_value");
            if (value instanceof BigDecimal marketValue) {
                row.put("market_allocation_percent", percent(marketValue, finalTotalMarket));
            }
        });
        BigDecimal cost = portfolio.totalOpenCostBasis();
        return Map.of("status", missing.isEmpty() ? "ok" : "partial", "valuation_basis", "LATEST_QUOTE",
                "base_currency", portfolio.baseCurrency(), "total_market_value", totalMarket,
                "total_cost_basis", cost, "unrealized_profit_loss", totalMarket.subtract(cost, MATH),
                "positions", rows, "missing_symbols", missing, "source", "twelve-data", "as_of", Instant.now());
    }

    private Map<String, Object> fx(Map<String, Object> arguments, String ownerId) {
        String pair = text(arguments, "pair");
        String base = text(arguments, "base_currency");
        String quote = text(arguments, "quote_currency");
        BigDecimal amount = decimal(arguments, "amount");
        if (amount != null && amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (!pair.isBlank()) {
            String[] parts = pair.toUpperCase(Locale.ROOT).split("[/_-]");
            if (parts.length != 2) throw new IllegalArgumentException("pair must look like USD/THB");
            base = parts[0];
            quote = parts[1];
        }
        if (amount != null && (base.isBlank() || quote.isBlank())) {
            throw new IllegalArgumentException("budget conversion requires explicit base_currency and quote_currency or pair");
        }
        if (base.isBlank()) base = investments.policy(ownerId).baseCurrency();
        if (quote.isBlank()) quote = base.equalsIgnoreCase("THB") ? "USD" : "THB";
        var rate = external.latestFxRate(base, quote);
        Map<String, Object> result = new LinkedHashMap<>(Map.of(
                "base_currency", rate.baseCurrency(), "quote_currency", rate.quoteCurrency(),
                "rate", rate.rate(), "date", rate.date(), "source", rate.source()));
        if (amount != null) {
            result.put("amount", amount);
            result.put("converted_amount", amount.multiply(rate.rate(), MATH).setScale(2, RoundingMode.HALF_UP));
            result.put("conversion_basis", "REFERENCE_RATE_BEFORE_BROKER_SPREAD_AND_FEES");
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> secFilings(Map<String, Object> arguments) {
        String symbol = text(arguments, "symbol");
        if (symbol.isBlank()) throw new IllegalArgumentException("symbol is required for sec_filings");
        int limit = integer(arguments, "limit", 5);
        List<InvestmentExternalDataService.SecFiling> filings = external.latestSecFilings(symbol, limit);
        List<Map<String, Object>> rows = filings.stream().map(filing -> Map.<String, Object>of(
                "symbol", filing.symbol(), "form", filing.form(), "filing_date", filing.filingDate(),
                "report_date", filing.reportDate(), "accession_number", filing.accessionNumber(), "url", filing.url())).toList();
        return Map.of("status", rows.isEmpty() ? "not_found" : "ok", "symbol", symbol.toUpperCase(Locale.ROOT),
                "filings", rows, "source", "sec-edgar");
    }

    private Map<String, Object> paperAccount() {
        if (!external.alpacaConfigured()) return setup("paper_account", "MINIKUN_INVESTMENT_ALPACA_KEY_ID and MINIKUN_INVESTMENT_ALPACA_SECRET");
        return Map.of("status", "ok", "paper", true, "account", external.alpacaPaperAccount());
    }

    private ToolResult paperOrder(ToolCallContext context, Map<String, Object> arguments, boolean confirmed) {
        if (!external.alpacaConfigured()) {
            return ToolResult.success(setup("paper_order", "MINIKUN_INVESTMENT_ALPACA_KEY_ID and MINIKUN_INVESTMENT_ALPACA_SECRET"));
        }
        if (!confirmed) {
            confirmations.save(context.conversationId(), context.ownerId(), "alpaca.paper_order", arguments);
            return ToolResult.success(Map.of("requires_confirmation", true,
                    "message", "นี่เป็นคำสั่งจำลองใน Alpaca Paper เท่านั้น ตรวจสอบแล้วตอบยืนยันเพื่อส่งคำสั่งครับ",
                    "paper", true, "proposed", proposal(arguments)));
        }
        PendingPlannerConfirmation pending = confirmations.find(context.conversationId(), context.ownerId())
                .filter(value -> value.action().equals("alpaca.paper_order"))
                .orElseThrow(() -> new IllegalArgumentException("no matching paper-order proposal is pending"));
        Map<String, Object> submitted = new LinkedHashMap<>();
        arguments.forEach((key, value) -> {
            if (key != null && !"confirmed".equals(key) && value != null) submitted.put(key, value);
        });
        if (!pending.arguments().equals(submitted)) {
            throw new IllegalArgumentException("confirmed paper-order arguments do not match the pending proposal");
        }
        Map<String, Object> result = external.submitAlpacaPaperOrder(
                text(arguments, "symbol"), decimal(arguments, "quantity"), text(arguments, "side"),
                textOr(arguments, "order_type", "market"), textOr(arguments, "time_in_force", "day"),
                decimal(arguments, "limit_price"));
        confirmations.clear(context.conversationId());
        return ToolResult.success(Map.of("submitted", true, "paper", true, "result", result));
    }

    private Map<String, Object> setup(String action, String variable) {
        return Map.of("status", "not_configured", "action", action, "setup_required", List.of("set " + variable));
    }

    private List<String> portfolioSymbols(String ownerId) {
        return investments.summary(ownerId).positions().stream().map(position -> position.symbol()).toList();
    }

    private List<String> symbols(Object value) {
        if (value == null) return List.of();
        return java.util.Arrays.stream(value.toString().split(","))
                .map(String::strip).filter(item -> !item.isBlank()).map(item -> item.toUpperCase(Locale.ROOT))
                .distinct().toList();
    }

    private Map<String, Object> proposal(Map<String, Object> arguments) {
        Map<String, Object> value = new LinkedHashMap<>();
        List<String> keys = List.of("symbol", "quantity", "side", "order_type", "time_in_force", "limit_price");
        keys.forEach(key -> {
            Object item = arguments.get(key);
            if (item != null && !item.toString().isBlank()) value.put(key, item);
        });
        return Map.copyOf(value);
    }

    private BigDecimal percent(BigDecimal value, BigDecimal total) {
        return total.signum() == 0 ? BigDecimal.ZERO
                : value.divide(total, MATH).multiply(HUNDRED).setScale(6, RoundingMode.HALF_UP);
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().strip();
    }

    private String textOr(Map<String, Object> arguments, String key, String fallback) {
        String value = text(arguments, key);
        return value.isBlank() ? fallback : value;
    }

    private BigDecimal decimal(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null || value.toString().isBlank()) return null;
        try { return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString()); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(key + " must be a valid decimal number"); }
    }

    private int integer(Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null || value.toString().isBlank()) return fallback;
        try { return Integer.parseInt(value.toString()); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(key + " must be an integer"); }
    }

    private boolean booleanValue(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value instanceof Boolean bool ? bool : value != null && Boolean.parseBoolean(value.toString());
    }

    private static Map<String, ToolParameter> parameters() {
        Map<String, ToolParameter> values = new LinkedHashMap<>();
        values.put("action", new ToolParameter("action", ToolParameterType.STRING, true,
                "quotes, portfolio_value, fx, sec_filings, paper_account, or paper_order."));
        values.put("symbols", new ToolParameter("symbols", ToolParameterType.STRING, false,
                "Comma-separated stock or ETF symbols for quotes."));
        values.put("symbol", new ToolParameter("symbol", ToolParameterType.STRING, false,
                "One symbol for quotes, SEC filings, or a paper order."));
        values.put("base_currency", new ToolParameter("base_currency", ToolParameterType.STRING, false,
                "Three-letter FX base currency."));
        values.put("quote_currency", new ToolParameter("quote_currency", ToolParameterType.STRING, false,
                "Three-letter FX quote currency."));
        values.put("pair", new ToolParameter("pair", ToolParameterType.STRING, false,
                "FX pair such as USD/THB."));
        values.put("amount", new ToolParameter("amount", ToolParameterType.NUMBER, false,
                "Positive budget in the FX base currency; optional for fx. Explicit currencies or pair are required. "
                        + "Returns converted_amount in quote currency before broker spread and fees."));
        values.put("limit", new ToolParameter("limit", ToolParameterType.INTEGER, false,
                "Maximum SEC filings to return, from 1 to 20."));
        values.put("quantity", new ToolParameter("quantity", ToolParameterType.NUMBER, false,
                "Positive quantity for an Alpaca paper order."));
        values.put("side", new ToolParameter("side", ToolParameterType.STRING, false,
                "buy or sell for an Alpaca paper order."));
        values.put("order_type", new ToolParameter("order_type", ToolParameterType.STRING, false,
                "market or limit for an Alpaca paper order."));
        values.put("time_in_force", new ToolParameter("time_in_force", ToolParameterType.STRING, false,
                "day or gtc for an Alpaca paper order."));
        values.put("limit_price", new ToolParameter("limit_price", ToolParameterType.NUMBER, false,
                "Positive limit price when order_type is limit."));
        values.put("confirmed", new ToolParameter("confirmed", ToolParameterType.BOOLEAN, false,
                "Must be true after explicit owner confirmation for paper_order."));
        return Map.copyOf(values);
    }
}
