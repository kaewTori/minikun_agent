package com.minikun.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.investment.InvestmentService;
import com.minikun.investment.InvestmentTransaction;
import com.minikun.investment.PortfolioPosition;
import com.minikun.investment.PortfolioSummary;
import com.minikun.planner.PendingPlannerConfirmation;
import com.minikun.planner.PlannerConfirmationService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Turns a clear owner-reported completed sale into one durable, confirmable ledger proposal. */
@Component
@Order(0)
@ConditionalOnProperty(name = "minikun.investment.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean({InvestmentService.class, PlannerConfirmationService.class})
public final class InvestmentTradeReportRouter implements ToolRequestRouter {
    private static final String TOOL_NAME = "investment.manage";
    private static final String PENDING_ACTION = "investment.add_transaction_batch";
    private static final Pattern COMPLETED_SALE = Pattern.compile(
            "(?iu)(cut\\s*loss|cut\\s*lost|ขาย(?:ไป|แล้ว|ทิ้ง|ออก)|sold|closed\\s+(?:the\\s+)?position)");
    private static final Pattern PROCEEDS = Pattern.compile(
            "(?iu)(ได้มา|ได้รับ|net\\s*(?:proceeds?)?|received|got)");
    private static final Pattern SALE_LINE = Pattern.compile(
            "(?iu)^\\s*(?:[-*•]\\s*)?([A-Z][A-Z0-9.-]{0,7})\\s+"
                    + "(?:(?:ขาย\\s*)?([0-9]+(?:\\.[0-9]+)?)\\s*"
                    + "(?:(?:หุ้น|shares?|units?)\\s*(?:(?:@|at|ราคา|price)\\s*)?|"
                    + "(?:@|at|ราคา|price)\\s*))?"
                    + "([0-9]+(?:\\.[0-9]+)?)\\s*([A-Z]{3})?"
                    + ".*?(?:ได้มา|ได้รับ|net\\s*(?:proceeds?)?|received|got)\\s*"
                    + "([0-9]+(?:\\.[0-9]+)?)\\s*([A-Z]{3})?\\s*$");
    private static final Pattern EXPLICIT_DATE = Pattern.compile(
            "(?iu)(?:วันที่|date|on)\\s*[:#]?\\s*"
                    + "(\\d{4}-\\d{2}-\\d{2}(?:[T\\s]\\d{2}:\\d{2}(?::\\d{2})?(?:Z|[+-]\\d{2}:?\\d{2})?)?)");
    private static final Pattern BARE_ISO_DATE = Pattern.compile(
            "(?<!\\d)(\\d{4}-\\d{2}-\\d{2}(?:T\\d{2}:\\d{2}(?::\\d{2})?(?:Z|[+-]\\d{2}:?\\d{2})?)?)(?!\\d)");
    private static final Pattern YESTERDAY = Pattern.compile("(?iu)(เมื่อวาน|yesterday)");
    private static final Pattern TODAY = Pattern.compile("(?iu)(วันนี้|today)");

    private final InvestmentService investments;
    private final PlannerConfirmationService confirmations;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InvestmentTradeReportRouter(
            InvestmentService investments,
            PlannerConfirmationService confirmations,
            ObjectMapper objectMapper,
            Clock clock) {
        this.investments = Objects.requireNonNull(investments, "investment service must not be null");
        this.confirmations = Objects.requireNonNull(confirmations, "investment confirmations must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "investment report clock must not be null");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId) {
        return route(userText, conversationId, "default");
    }

    @Override
    public Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId) {
        if (userText == null || userText.isBlank() || conversationId == null) return Optional.empty();
        if (isConfirmation(userText)) return confirm(conversationId, ownerId);
        if (!isReportCandidate(userText)) return Optional.empty();
        return Optional.of(propose(userText, conversationId, ownerId));
    }

    private Optional<ToolEvidence> confirm(ConversationId conversationId, String ownerId) {
        Optional<PendingPlannerConfirmation> pending = confirmations.find(conversationId, ownerId)
                .filter(value -> PENDING_ACTION.equals(value.action()));
        if (pending.isEmpty()) return Optional.empty();
        try {
            List<Map<String, Object>> rows = objectMapper.convertValue(
                    pending.get().arguments().get("transactions"),
                    new TypeReference<List<Map<String, Object>>>() { });
            List<InvestmentService.TransactionInput> inputs = rows.stream()
                    .map(this::transactionInput)
                    .toList();
            String fingerprint = text(pending.get().arguments(), "report_fingerprint");
            List<InvestmentTransaction> saved = investments.addTransactions(
                    ownerId, conversationId.value(), inputs, fingerprint);
            confirmations.clear(conversationId);
            return Optional.of(ToolEvidence.finalVerified(TOOL_NAME, savedMessage(saved)));
        } catch (IllegalArgumentException exception) {
            return Optional.of(ToolEvidence.finalFailed(TOOL_NAME,
                    "ขออภัยครับ บันทึกรายการขายไม่สำเร็จ: " + exception.getMessage()));
        } catch (RuntimeException exception) {
            return Optional.of(ToolEvidence.finalFailed(TOOL_NAME,
                    "ขออภัยครับ บันทึกรายการขายไม่สำเร็จ จึงยังไม่ล้างรายการรอยืนยันครับ"));
        }
    }

    private ToolEvidence propose(String text, ConversationId conversationId, String ownerId) {
        try {
            PortfolioSummary portfolio = investments.summary(ownerId);
            Map<String, PortfolioPosition> positions = portfolio.positions().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            position -> position.symbol().toUpperCase(Locale.ROOT),
                            position -> position, (first, ignored) -> first, LinkedHashMap::new));
            String account = uniqueAccount(ownerId);
            List<ReportedSale> sales = parse(text, portfolio.baseCurrency());
            Instant occurredAt = occurredAt(text);
            String fingerprint = fingerprint(account, occurredAt, sales);
            if (alreadyRecorded(ownerId, fingerprint)) {
                throw new IllegalArgumentException("รายการรายงานนี้ถูกบันทึกไปแล้วใน ledger");
            }
            List<Map<String, Object>> transactions = new ArrayList<>();
            List<String> preview = new ArrayList<>();
            for (ReportedSale sale : sales) {
                PortfolioPosition position = positions.get(sale.symbol());
                if (position == null || position.quantity().signum() <= 0) {
                    throw new IllegalArgumentException("ไม่พบจำนวนถืออยู่ของ " + sale.symbol() + " ใน ledger");
                }
                BigDecimal quantity = sale.quantity() == null ? position.quantity() : sale.quantity();
                if (quantity.signum() <= 0 || quantity.compareTo(position.quantity()) > 0) {
                    throw new IllegalArgumentException("จำนวนขายของ " + sale.symbol()
                            + " ต้องมากกว่า 0 และไม่เกินจำนวนที่ถืออยู่ " + position.quantity());
                }
                BigDecimal gross = quantity.multiply(sale.unitPrice());
                BigDecimal fee = gross.subtract(sale.netProceeds());
                if (fee.signum() < 0) {
                    throw new IllegalArgumentException(
                            sale.symbol() + " มียอดสุทธิมากกว่ามูลค่าขายก่อนหักค่าธรรมเนียม");
                }
                Map<String, Object> transaction = new LinkedHashMap<>();
                transaction.put("account", account);
                transaction.put("type", "SELL");
                transaction.put("symbol", sale.symbol());
                transaction.put("instrument_name", position.instrumentName());
                transaction.put("asset_class", position.assetClass());
                transaction.put("currency", sale.currency());
                transaction.put("quantity", quantity);
                transaction.put("unit_price", sale.unitPrice());
                transaction.put("net_proceeds", sale.netProceeds());
                transaction.put("fee", fee);
                transaction.put("occurred_at", occurredAt.toString());
                transaction.put("note", "Owner-reported completed sale; "
                        + (sale.quantity() == null ? "full recorded holding" : "partial quantity reported")
                        + " report_fingerprint=" + fingerprint);
                transactions.add(transaction);
                preview.add("• " + sale.symbol() + ": " + quantity + " หน่วย @ "
                        + sale.unitPrice() + " " + sale.currency() + " | สุทธิ "
                        + sale.netProceeds() + " " + sale.currency() + " | ค่าธรรมเนียม "
                        + fee.stripTrailingZeros().toPlainString() + " " + sale.currency());
            }
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("action", "add_transaction_batch");
            arguments.put("report_fingerprint", fingerprint);
            arguments.put("transactions", transactions);
            confirmations.save(conversationId, ownerId, PENDING_ACTION, arguments);
            return ToolEvidence.pendingConfirmation(TOOL_NAME,
                    "มินิคุงอ่านเป็นรายการขายที่เกิดขึ้นแล้ว และเตรียมบันทึกตามข้อมูลที่เรารายงาน:\n"
                            + String.join("\n", preview)
                            + "\nบัญชี: " + account + " | ใช้จำนวนถือจริงล่าสุดจาก ledger"
                            + "\nวันที่รายการ: " + occurredAt
                            + "\nยังไม่บันทึกจริงจนกว่าจะพิมพ์ ‘ยืนยัน’ ครับ");
        } catch (IllegalArgumentException exception) {
            return ToolEvidence.finalFailed(TOOL_NAME,
                    "ยังเตรียมบันทึกรายการขายไม่ได้: " + exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolEvidence.finalFailed(TOOL_NAME,
                    "ยังเตรียมบันทึกรายการขายไม่ได้ เพราะอ่าน ledger ไม่สำเร็จครับ");
        }
    }

    private List<ReportedSale> parse(String text, String baseCurrency) {
        List<ReportedSale> sales = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (!PROCEEDS.matcher(line).find()) continue;
            Matcher matcher = SALE_LINE.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("อ่านบรรทัดรายการขายไม่สำเร็จ: " + line.trim());
            }
            String symbol = matcher.group(1).toUpperCase(Locale.ROOT);
            String currency = first(matcher.group(6), matcher.group(4), baseCurrency).toUpperCase(Locale.ROOT);
            if (!currency.equals(baseCurrency)) {
                throw new IllegalArgumentException("phase-one ledger รองรับสกุลเงิน " + baseCurrency
                        + " เท่านั้น แต่ " + symbol + " เป็น " + currency);
            }
            BigDecimal quantity = matcher.group(2) == null ? null : decimal(matcher.group(2), "จำนวนขาย " + symbol);
            BigDecimal unitPrice = decimal(matcher.group(3), "ราคาขาย " + symbol);
            BigDecimal netProceeds = decimal(matcher.group(5), "เงินสุทธิ " + symbol);
            if (sales.stream().anyMatch(value -> value.symbol().equals(symbol))) {
                throw new IllegalArgumentException("มีรายการ " + symbol + " ซ้ำในรายงานเดียวกัน");
            }
            sales.add(new ReportedSale(symbol, quantity, unitPrice, netProceeds, currency));
        }
        if (sales.isEmpty()) {
            throw new IllegalArgumentException("ต้องมีบรรทัดรูปแบบ SYMBOL ราคาขาย ... ได้มา เงินสุทธิ");
        }
        return List.copyOf(sales);
    }

    private InvestmentService.TransactionInput transactionInput(Map<String, Object> row) {
        return new InvestmentService.TransactionInput(
                text(row, "account"), text(row, "type"), text(row, "symbol"), text(row, "instrument_name"),
                text(row, "asset_class"), text(row, "currency"), decimal(row, "quantity"),
                decimal(row, "unit_price"), decimal(row, "amount"), decimal(row, "fee"),
                instant(row, "occurred_at"), text(row, "note"));
    }

    private String uniqueAccount(String ownerId) {
        List<String> accounts = investments.transactions(ownerId).stream()
                .map(InvestmentTransaction::account).filter(value -> !value.isBlank()).distinct().toList();
        if (accounts.size() != 1) {
            throw new IllegalArgumentException(accounts.isEmpty()
                    ? "ไม่พบ investment account ใน ledger"
                    : "พบหลาย investment account กรุณาระบุ account ก่อน");
        }
        return accounts.getFirst();
    }

    private String savedMessage(List<InvestmentTransaction> saved) {
        StringBuilder content = new StringBuilder("ยืนยันแล้วครับ บันทึกรายการขายตามที่เรารายงานลง investment ledger เรียบร้อยแล้ว\n");
        saved.forEach(transaction -> content.append("• ").append(transaction.symbol())
                .append(": ").append(transaction.quantity()).append(" หน่วย @ ")
                .append(transaction.unitPrice()).append(" ").append(transaction.currency())
                .append(" | fee ").append(transaction.fee()).append(" ")
                .append(transaction.currency()).append("\n"));
        content.append("การดำเนินการนี้บันทึกข้อมูลย้อนหลังเท่านั้น ไม่ได้ส่งคำสั่งซื้อขายจริงครับ");
        return content.toString().trim();
    }

    private Instant occurredAt(String text) {
        String value = firstDate(text, EXPLICIT_DATE, BARE_ISO_DATE);
        if (value != null) return parseDate(value);
        LocalDate today = LocalDate.now(clock);
        if (YESTERDAY.matcher(text).find()) return endOfDay(today.minusDays(1));
        if (TODAY.matcher(text).find()) return endOfDay(today);
        return clock.instant();
    }

    private String firstDate(String text, Pattern... patterns) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) return matcher.group(1);
        }
        return null;
    }

    private Instant parseDate(String value) {
        try {
            if (value.length() == 10) return endOfDay(LocalDate.parse(value));
            String normalized = value.replace(' ', 'T');
            try {
                return OffsetDateTime.parse(normalized).toInstant();
            } catch (RuntimeException ignored) {
                return LocalDateTime.parse(normalized).atZone(clock.getZone()).toInstant();
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("วันที่ต้องเป็น YYYY-MM-DD หรือ ISO-8601");
        }
    }

    private Instant endOfDay(LocalDate date) {
        return date.atTime(23, 59, 59).atZone(clock.getZone()).toInstant();
    }

    private boolean alreadyRecorded(String ownerId, String fingerprint) {
        return investments.transactions(ownerId).stream()
                .filter(InvestmentTransaction::active)
                .anyMatch(transaction -> transaction.note().contains("report_fingerprint=" + fingerprint));
    }

    private String fingerprint(String account, Instant occurredAt, List<ReportedSale> sales) {
        String canonical = account + "|" + occurredAt.atZone(clock.getZone()).toLocalDate() + "|"
                + sales.stream()
                        .map(sale -> String.join("|", sale.symbol(),
                                sale.quantity() == null ? "FULL" : canonical(sale.quantity()),
                                canonical(sale.unitPrice()), canonical(sale.netProceeds()), sale.currency()))
                        .sorted()
                        .collect(Collectors.joining("||"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String canonical(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private boolean isReportCandidate(String text) {
        return PROCEEDS.matcher(text).find()
                && (COMPLETED_SALE.matcher(text).find() || hasSaleLine(text));
    }

    private boolean hasSaleLine(String text) {
        for (String line : text.split("\\R")) {
            if (SALE_LINE.matcher(line).matches()) return true;
        }
        return false;
    }

    private boolean isConfirmation(String text) {
        String normalized = text.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[.!?,，。!?]+$", "")
                .replaceAll("(ครับ|ค่ะ|คะ|นะ)$", "").trim();
        return switch (normalized) {
            case "ยืนยัน", "ใช่", "ตกลง", "ได้เลย", "ทำเลย", "confirm", "confirmed", "yes", "ok", "okay" -> true;
            default -> false;
        };
    }

    private String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private BigDecimal decimal(String value, String field) {
        try {
            return new BigDecimal(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " ต้องเป็นตัวเลข");
        }
    }

    private BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null || value.toString().isBlank() ? null : decimal(value.toString(), key);
    }

    private Instant instant(Map<String, Object> row, String key) {
        try {
            return Instant.parse(text(row, key));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(key + " ต้องเป็น ISO-8601 instant");
        }
    }

    private String text(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private record ReportedSale(
            String symbol, BigDecimal quantity, BigDecimal unitPrice, BigDecimal netProceeds, String currency) { }
}
