package com.minikun.investment;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/** Shared by deterministic routers and chat planning so advice reaches the tool loop. */
public final class InvestmentAdviceIntent {
    private static final Pattern TARGET = Pattern.compile(
            "(?iu)(พอร์ต|หุ้น|ลงทุน|สินทรัพย์|กองทุน|ETF|\\bport\\b|portfolio|holdings?|invest|stocks?|shares?)");
    private static final Pattern LEDGER_WRITE = Pattern.compile(
            "(?iu)(อัปเดต|อัพเดต|อัพเดท|บันทึก|เพิ่มรายการ|เพิ่มธุรกรรม|แก้ไข|update|record|log|register)");
    private static final Pattern COMPLETED_TRADE = Pattern.compile(
            "(?iu)(ซื้อ|ขาย|เติม).{0,60}(แล้ว|เรียบร้อย)|\\b(bought|sold)\\b");
    private static final Pattern HYPOTHETICAL = Pattern.compile("(?iu)(^\\s*(?:ถ้า|สมมติ|if\\b|suppose\\b)|ควร|\\bshould\\b)");
    private static final Pattern SYMBOL = Pattern.compile("\\b[A-Z][A-Z0-9.-]{1,7}\\b");
    private static final Pattern TRADE_OPINION = Pattern.compile("(?iu)(ซื้อ|ขาย|ถัว|buy|sell|hold)");
    private static final Pattern ADVICE = Pattern.compile(
            "(?iu)(แนะนำ|ควร|อะไร(?:เพิ่ม|ต่อ)?ดี|ตัวไหนดี|ดีไหม|ดีมั้ย|เติม|จัดสรร|ปรับสมดุล|recommend|advice|should|allocate|rebalance)");
    private static final Pattern BUDGET_TOP_UP = Pattern.compile(
            "(?iu)(เติม(?=พอร์ต|หุ้น|อะไร|ตัวไหน)|เอาไปลงทุน|invest|buy).*(?:อะไรดี|ไหนดี|ดีไหม|ดีมั้ย|should|recommend|\\?)");
    private static final Pattern MONEY = Pattern.compile("(?iu)([0-9][0-9,.]*\\s*(?:บาท|THB|USD|baht|ดอลลาร์)|\\$\\s*[0-9])");
    private static final Pattern BUDGET = Pattern.compile(
            "(?iu)(?<![0-9,.-])(?:([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*(บาท|THB|USD|baht|ดอลลาร์)"
                    + "|\\$\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?))");
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "(?iu)^(?:(?:เปลี่ยน|ปรับ|ลด|เพิ่ม|งบ|change|reduce|increase|budget).*(?:[0-9][0-9,.]*\\s*(?:บาท|THB|USD|baht|ดอลลาร์)|\\$\\s*[0-9])"
                    + "|(?:ขอ|แปลง|คิด|เอา|convert|in\\b).*(?:\\$|ดอลลาร์|USD|dollars?|บาท|THB|baht)).*$");
    private static final Pattern USER_TURN = Pattern.compile("(?ms)^user: (.*?)(?=^(?:user|assistant): |\\z)");

    private InvestmentAdviceIntent() { }

    public record Budget(BigDecimal amount, String currency) { }

    public static Optional<Budget> budget(String message) {
        var matcher = BUDGET.matcher(message == null ? "" : message);
        if (!matcher.find()) return Optional.empty();
        BigDecimal amount = new BigDecimal((matcher.group(1) == null ? matcher.group(3) : matcher.group(1)).replace(",", ""));
        String currency = matcher.group(2);
        String code = currency != null && currency.matches("(?iu)(บาท|THB|baht)") ? "THB" : "USD";
        // ponytail: one explicit money amount; multiple amounts need model clarification, never guess a total.
        return amount.signum() > 0 && !matcher.find() ? Optional.of(new Budget(amount, code)) : Optional.empty();
    }

    public static boolean matches(String message) {
        if (message == null || message.isBlank() || requestsLedgerUpdate(message)) return false;
        return (TARGET.matcher(message).find()
                || SYMBOL.matcher(message).find() && TRADE_OPINION.matcher(message).find())
                && ADVICE.matcher(message).find()
                || MONEY.matcher(message).find() && BUDGET_TOP_UP.matcher(message).find();
    }

    public static boolean matches(String message, String conversationContext) {
        if (requestsLedgerUpdate(message)) return false;
        if (matches(message)) return true;
        if (!isFollowUp(message) || conversationContext == null) return false;
        var turns = new ArrayList<String>();
        var users = USER_TURN.matcher(conversationContext);
        while (users.find()) turns.add(users.group(1).strip());
        for (String previous : turns.reversed()) {
            if (matches(previous)) return true;
            if (!isFollowUp(previous)) return false;
        }
        return false;
    }

    public static boolean requestsLedgerUpdate(String message) {
        if (message == null || message.isBlank()) return false;
        boolean target = TARGET.matcher(message).find() || SYMBOL.matcher(message).find();
        return target && (LEDGER_WRITE.matcher(message).find()
                || COMPLETED_TRADE.matcher(message).find() && !HYPOTHETICAL.matcher(message).find());
    }

    private static boolean isFollowUp(String message) {
        return message != null && message.length() <= 160 && FOLLOW_UP.matcher(message.strip()).matches();
    }
}
