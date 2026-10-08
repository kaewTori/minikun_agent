package com.minikun.investment;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The model selects proportions and explains them; Java owns every displayed portfolio number. */
public final class InvestmentAdvicePlan {
    public record Allocation(String symbol, BigDecimal weight) { }
    public record Plan(List<Allocation> allocations, String rationale, List<String> avoidSymbols,
                       List<Integer> sources) { }
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Pattern PREVIOUS_ROW = Pattern.compile(
            "(?m)^• \\*\\*([A-Z][A-Z0-9.-]*):\\*\\* ([0-9,]+\\.[0-9]{2}) (บาท|USD|[A-Z]{3})");

    private InvestmentAdvicePlan() { }

    public static Map<String, Object> schema(JsonNode portfolio, int sourceCount) {
        List<String> symbols = new ArrayList<>();
        portfolio.path("positions").forEach(position -> symbols.add(position.path("symbol").asText()));
        List<String> held = List.copyOf(symbols);
        symbols.add("CASH");
        Map<String, Object> text = Map.of("type", "string", "maxLength", 500);
        return Map.of("type", "object", "additionalProperties", false,
                "required", List.of("allocations", "rationale", "avoid_symbols", "sources"),
                "properties", Map.of(
                        "allocations", Map.of("type", "array", "minItems", 1, "maxItems", 3, "items",
                                Map.of("type", "object", "additionalProperties", false,
                                        "required", List.of("symbol", "weight"), "properties", Map.of(
                                                "symbol", Map.of("type", "string", "enum", symbols),
                                                "weight", Map.of("type", "integer", "minimum", 1, "maximum", 100)))),
                        "rationale", text,
                        "avoid_symbols", Map.of("type", "array", "maxItems", 3, "uniqueItems", true,
                                "items", Map.of("type", "string", "enum", held)),
                        "sources", Map.of("type", "array", "maxItems", 3, "uniqueItems", true,
                                "items", Map.of("type", "integer", "minimum", 0, "maximum", Math.max(0, sourceCount - 1)))));
    }

    public static Plan validate(JsonNode draft, JsonNode portfolio, int sourceCount) {
        if (!draft.isObject() || !draft.path("avoid_symbols").isArray() || !draft.path("sources").isArray()
                || !draft.path("rationale").isTextual())
            throw invalid("plan requires allocations, Thai rationale, avoid_symbols and sources");
        Set<String> held = new HashSet<>();
        portfolio.path("positions").forEach(position -> held.add(position.path("symbol").asText()));
        if (!draft.path("allocations").isArray() || draft.path("allocations").isEmpty()
                || draft.path("allocations").size() > 3) throw invalid("choose one to three allocations");
        List<Allocation> allocations = new ArrayList<>();
        Set<String> selected = new HashSet<>();
        for (JsonNode item : draft.path("allocations")) {
            String symbol = item.path("symbol").asText();
            if (!held.contains(symbol) && !"CASH".equals(symbol)) throw invalid("unknown symbol: " + symbol);
            if (!selected.add(symbol)) throw invalid("duplicate symbol: " + symbol);
            JsonNode share = item.path("weight");
            if (!share.isIntegralNumber() || !share.canConvertToInt() || share.asInt() < 1 || share.asInt() > 100)
                throw invalid("weight must be an integer from 1 to 100");
            allocations.add(new Allocation(symbol, share.decimalValue()));
        }
        List<String> avoided = new ArrayList<>();
        for (JsonNode item : draft.path("avoid_symbols")) {
            String symbol = item.asText();
            if (!held.contains(symbol) || selected.contains(symbol) || avoided.contains(symbol))
                throw invalid("avoid_symbols must identify distinct held positions outside this plan");
            avoided.add(symbol);
        }
        if (avoided.size() > 3) throw invalid("at most three avoided positions");
        List<Integer> sources = new ArrayList<>();
        for (JsonNode item : draft.path("sources")) {
            if (!item.isIntegralNumber() || item.asInt() < 0 || item.asInt() >= sourceCount || sources.contains(item.asInt()))
                throw invalid("sources must refer to the supplied evidence indices");
            sources.add(item.asInt());
        }
        return new Plan(List.copyOf(allocations), prose(draft.path("rationale").asText(), true),
                List.copyOf(avoided), List.copyOf(sources));
    }

    public static Map<String, BigDecimal> amounts(Plan plan, BigDecimal budget) {
        if (budget == null || budget.signum() <= 0) throw invalid("budget must be positive");
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        BigDecimal remaining = budget.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalWeight = plan.allocations().stream().map(Allocation::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
        for (int i = 0; i < plan.allocations().size(); i++) {
            Allocation allocation = plan.allocations().get(i);
            BigDecimal amount = i == plan.allocations().size() - 1 ? remaining
                    : budget.multiply(allocation.weight()).divide(totalWeight, 2, RoundingMode.DOWN);
            if (amount.signum() <= 0) throw invalid("budget is too small for this split; simplify the plan");
            result.put(allocation.symbol(), amount);
            remaining = remaining.subtract(amount);
        }
        return result;
    }

    public static Map<String, BigDecimal> projectedWeights(Plan plan, JsonNode portfolio, BigDecimal convertedBudget) {
        Map<String, BigDecimal> extra = amounts(plan, convertedBudget);
        BigDecimal invested = extra.entrySet().stream().filter(entry -> !"CASH".equals(entry.getKey()))
                .map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = decimal(portfolio, "totalOpenCostBasis").add(invested);
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        for (JsonNode position : portfolio.path("positions")) {
            String symbol = position.path("symbol").asText();
            BigDecimal cost = decimal(position, "costBasis").add(extra.getOrDefault(symbol, BigDecimal.ZERO));
            weights.put(symbol, total.signum() == 0 ? BigDecimal.ZERO : cost.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP));
        }
        return weights;
    }

    public static List<String> breaches(Plan plan, JsonNode portfolio, BigDecimal convertedBudget) {
        JsonNode limit = portfolio.path("policy").path("maxSinglePositionPercent");
        if (!limit.isNumber()) return List.of();
        Map<String, BigDecimal> weights = projectedWeights(plan, portfolio, convertedBudget);
        return plan.allocations().stream().map(Allocation::symbol).filter(symbol -> weights.containsKey(symbol)
                && weights.get(symbol).compareTo(limit.decimalValue()) > 0).toList();
    }

    public static Plan previous(String response) {
        var rows = PREVIOUS_ROW.matcher(response == null ? "" : response);
        Map<String, BigDecimal> money = new LinkedHashMap<>();
        String currency = null;
        while (rows.find()) {
            if (currency != null && !currency.equals(rows.group(3))) throw invalid("mixed previous plan currencies");
            currency = rows.group(3);
            if (money.put(rows.group(1), new BigDecimal(rows.group(2).replace(",", ""))) != null)
                throw invalid("duplicate previous allocation");
        }
        if (money.isEmpty() || money.size() > 3) return null;
        List<Allocation> allocations = new ArrayList<>();
        for (var entry : money.entrySet()) {
            if (entry.getValue().signum() <= 0) throw invalid("invalid previous plan amount");
            allocations.add(new Allocation(entry.getKey(), entry.getValue()));
        }
        return new Plan(List.copyOf(allocations), "", List.of(), List.of());
    }

    public static String render(Plan plan, JsonNode portfolio, InvestmentAdviceIntent.Budget budget,
                                JsonNode fx, List<JsonNode> sources, boolean conversionOnly) {
        Map<String, BigDecimal> sourceAmounts = amounts(plan, budget.amount());
        String fxDate = fxDate(fx.path("date"));
        boolean converted = fx.path("converted_amount").isNumber() && fx.path("amount").isNumber()
                && fx.path("amount").decimalValue().compareTo(budget.amount()) == 0
                && budget.currency().equals(fx.path("base_currency").asText()) && fx.path("rate").decimalValue().signum() > 0
                && !fxDate.isBlank() && !fx.path("source").asText().isBlank();
        String target = fx.path("quote_currency").asText();
        Map<String, BigDecimal> convertedAmounts = converted ? amounts(plan, fx.path("converted_amount").decimalValue()) : Map.of();
        boolean convertedOutput = conversionOnly && converted;
        StringBuilder answer = new StringBuilder(conversionOnly ? "ยอดตามแผนล่าสุดครับ\n" : "แนะนำเติมรอบนี้ครับ\n");
        sourceAmounts.forEach((symbol, amount) -> answer.append("• **").append(symbol).append(":** ")
                .append(convertedOutput ? convertedAmounts.get(symbol).toPlainString() : amount.toPlainString())
                .append(' ').append(label(convertedOutput ? target : budget.currency()))
                .append(converted && !budget.currency().equals(target) && !convertedOutput
                        ? " (~" + money(convertedAmounts.get(symbol), target) + ")" : "").append('\n'));
        if (conversionOnly) {
            if (!converted) return "ยังแปลงยอดตามแผนเป็นดอลลาร์ไม่ได้ครับ เพราะยังไม่มี FX ที่ตรวจสอบได้";
            answer.append("รวม ").append(money(fx.path("converted_amount").decimalValue(), target)).append("\n");
        } else {
            answer.append("\n").append(plan.rationale()).append("\n");
            Map<String, BigDecimal> after = converted && portfolio.path("baseCurrency").asText().equals(target)
                    ? projectedWeights(plan, portfolio, fx.path("converted_amount").decimalValue()) : Map.of();
            for (Allocation allocation : plan.allocations()) {
                for (JsonNode position : portfolio.path("positions")) {
                    if (!allocation.symbol().equals(position.path("symbol").asText())) continue;
                    answer.append("\n").append(allocation.symbol()).append(" มีสัดส่วนตามต้นทุน ")
                            .append(decimal(position, "costAllocationPercent").setScale(2, RoundingMode.HALF_UP)).append('%');
                    if (after.containsKey(allocation.symbol())) answer.append(" → ").append(after.get(allocation.symbol()))
                            .append("% หลังเติมตามแผน");
                }
            }
            for (String symbol : plan.avoidSymbols()) {
                for (JsonNode position : portfolio.path("positions")) if (symbol.equals(position.path("symbol").asText())) {
                    answer.append("\nรอบนี้ยังไม่เติม ").append(symbol).append(" ซึ่งมีสัดส่วนตามต้นทุน ")
                            .append(decimal(position, "costAllocationPercent").setScale(2, RoundingMode.HALF_UP)).append('%');
                }
            }
            if (!after.isEmpty()) {
                List<String> over = breaches(plan, portfolio, fx.path("converted_amount").decimalValue());
                if (!over.isEmpty()) answer.append("\n\nแผนนี้ทำให้ ").append(String.join(", ", over))
                        .append(" เกินเพดานสัดส่วน ").append(portfolio.path("policy").path("maxSinglePositionPercent").decimalValue().stripTrailingZeros().toPlainString())
                        .append("% ที่ใช้อยู่ จึงต้องทบทวนเพดานหรือปรับแผนก่อนซื้อ ไม่ได้เปลี่ยนนโยบายให้ครับ");
            }
            if (portfolio.path("policy").path("goal").asText().isBlank())
                answer.append("\n\nคำแนะนำนี้สมมติว่าเป็นเงินลงทุนระยะยาวและรับความผันผวนได้ เพราะยังไม่ได้ระบุเป้าหมายครับ");
            answer.append("\nอิงสัดส่วนตามต้นทุน ไม่ใช่มูลค่าตลาดวันนี้; สินทรัพย์ยังขาดทุนได้และค่าเงินมีผลต่อมูลค่าเมื่อแลกกลับครับ");
            for (int index : plan.sources()) {
                JsonNode source = sources.get(index);
                answer.append("\n[ข้อมูลประกอบ](").append(source.path("url").asText()).append(')');
            }
        }
        if (converted) answer.append("\nFX ").append(fx.path("base_currency").asText()).append('/')
                .append(target).append(" = ").append(fx.path("rate").asText()).append(" วันที่ ")
                .append(fxDate).append(" จาก ").append(fx.path("source").asText())
                .append("; ยอดจริงอาจต่างจาก spread และค่าธรรมเนียมของ broker ครับ");
        else answer.append("\nยังไม่มี FX ที่ตรวจสอบได้ จึงแสดงเฉพาะงบเดิมและยังไม่คำนวณสัดส่วนหลังซื้อครับ");
        return answer.toString();
    }

    public static Plan corePlan(JsonNode portfolio, List<JsonNode> sources, String question) {
        String mandate = portfolio.path("policy").path("goal").asText() + " "
                + portfolio.path("policy").path("timeHorizon").asText() + " "
                + portfolio.path("policy").path("riskTolerance").asText() + " " + question;
        if (mandate.matches("(?isu).*(ปันผล|รายได้|กระแสเงินสด|ระยะสั้น|เดือนหน้า|เงินสำรอง|ฉุกเฉิน|เสี่ยงต่ำ|dividend|income|emergency|short.term).*")
                || portfolio.path("policy").path("riskTolerance").asText().matches("(?iu).*(ต่ำ|low|อนุรักษ).*" )
                || question.matches("(?isu).*(เพดาน|นโยบาย|policy|limit).*")
                || portfolio.path("positions").size() < 2) return null;
        Map<String, Integer> cores = new LinkedHashMap<>();
        BigDecimal coreWeight = BigDecimal.ZERO, otherFunds = BigDecimal.ZERO;
        for (JsonNode position : portfolio.path("positions")) {
            if (!"ETF".equalsIgnoreCase(position.path("assetClass").asText())) continue;
            String symbol = position.path("symbol").asText();
            if (Pattern.compile("(?i)(?<![A-Z0-9])" + Pattern.quote(symbol) + "(?![A-Z0-9])").matcher(question).find()) return null;
            for (int i = 0; i < sources.size(); i++) {
                JsonNode source = sources.get(i);
                String subject = source.path("content").asText();
                subject = subject.substring(0, Math.min(400, subject.length())) + " "
                        + source.path("url").asText().replace('-', ' ');
                // ponytail: issuer-page phrase confirms total-stock-market exposure; add verified fund profiles for other broad indices.
                if (source.path("url").asText().toLowerCase(java.util.Locale.ROOT).contains(symbol.toLowerCase(java.util.Locale.ROOT))
                        && subject.matches("(?isu).*(total\\s+(?:world\\s+)?stock\\s+market|overall\\s+stock\\s+market|entire\\s+stock\\s+market).*")
                        && !source.path("content").asText().matches("(?isu).*(leveraged|inverse\\s+(?:ETF|fund)).*")) {
                    cores.put(symbol, i); break;
                }
            }
            if (cores.containsKey(symbol)) coreWeight = coreWeight.add(decimal(position, "costAllocationPercent"));
            else otherFunds = otherFunds.add(decimal(position, "costAllocationPercent"));
        }
        if (cores.isEmpty() || coreWeight.compareTo(otherFunds) >= 0) return null;
        JsonNode chosen = null;
        List<JsonNode> otherPositions = new ArrayList<>();
        for (JsonNode position : portfolio.path("positions")) {
            String symbol = position.path("symbol").asText();
            if (cores.containsKey(symbol)) {
                if (chosen == null || decimal(position, "costAllocationPercent").compareTo(decimal(chosen, "costAllocationPercent")) < 0) chosen = position;
            } else otherPositions.add(position);
        }
        String symbol = chosen.path("symbol").asText();
        otherPositions.sort((a, b) -> decimal(b, "costAllocationPercent").compareTo(decimal(a, "costAllocationPercent")));
        return new Plan(List.of(new Allocation(symbol, BigDecimal.ONE)),
                "รอบนี้ให้เงินใหม่เสริม " + symbol + " ซึ่งอิงตลาดหุ้นโดยรวมตามข้อมูลผู้ออกกองทุนครับ "
                        + "แกนนี้ยังมีน้ำหนักน้อยเมื่อเทียบกับกองทุนส่วนอื่น จึงช่วยให้เงินใหม่กระจายผ่านตลาดกว้าง "
                        + "และลดน้ำหนักสัมพัทธ์ของสินทรัพย์เดิมที่ไม่ได้เติม ไม่จำเป็นต้องแบ่งเงินก้อนเล็กไปเติมทุกตัวครับ",
                otherPositions.stream().limit(2).map(p -> p.path("symbol").asText()).toList(), List.of(cores.get(symbol)));
    }

    private static String fxDate(JsonNode value) {
        try {
            if (value.isTextual()) return java.time.LocalDate.parse(value.asText()).toString();
            if (value.isArray() && value.size() == 3) return java.time.LocalDate.of(value.get(0).asInt(), value.get(1).asInt(), value.get(2).asInt()).toString();
        } catch (RuntimeException ignored) { }
        return "";
    }

    private static String prose(String text, boolean required) {
        if ((required && text.isBlank()) || text.length() > 500 || text.codePoints().anyMatch(Character::isDigit)
                || text.contains("http") || text.contains("$") || text.contains("%")
                || text.matches("(?is).*(รับประกัน|เสี่ยงต่ำ|ไม่มีความเสี่ยง|รักษาเงินต้น|เริ่มต้นธุรกิจ|ขายของ|มือใหม่|system prompt|paper_order|tool call).*")
                || !text.isBlank() && !text.codePoints().anyMatch(c -> c >= 0x0E01 && c <= 0x0E5B))
            throw invalid("write concise Thai reasoning without numbers, URLs, low-risk claims or tool narration; Java renders all figures");
        return text.strip();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (!node.path(field).isNumber()) throw invalid("missing verified numeric field: " + field);
        return node.path(field).decimalValue();
    }
    private static String label(String currency) { return "THB".equals(currency) ? "บาท" : currency; }
    private static String money(BigDecimal amount, String currency) { return ("USD".equals(currency) ? "$" : "")
            + amount.setScale(2, RoundingMode.HALF_UP).toPlainString() + ("USD".equals(currency) ? "" : " " + label(currency)); }
    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
}
