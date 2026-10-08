package com.minikun.investment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InvestmentAdvicePlanTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void underweightBroadCoreUsesIssuerEvidenceAndChangesWithPortfolioOrMandate() throws Exception {
        JsonNode source = mapper.readTree("""
                {"url":"https://advisors.vanguard.com/investments/products/vti/vanguard-total-stock-market-etf",
                "content":"VTI Total Stock Market fund tracks the overall stock market."}
                """);
        JsonNode portfolio = portfolio(mapper);
        var plan = InvestmentAdvicePlan.corePlan(portfolio, List.of(source), "เรามีงบอยู่ 1000 บาท เอาไปลงทุนอะไรเพิ่มดี");
        assertNotNull(plan);
        assertEquals(List.of("VTI"), plan.allocations().stream().map(InvestmentAdvicePlan.Allocation::symbol).toList());
        assertEquals(List.of("SCHD", "QQQM"), plan.avoidSymbols());
        assertNull(InvestmentAdvicePlan.corePlan(portfolio, List.of(), "เพิ่มเงินลงทุน"), "fund roles require issuer evidence");
        assertNull(InvestmentAdvicePlan.corePlan(portfolio, List.of(source), "อยากเน้นปันผล"));
        assertNull(InvestmentAdvicePlan.corePlan(portfolio, List.of(source), "อยากเพิ่ม SCHD"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) portfolio.path("positions").get(7)).put("costAllocationPercent", 80);
        assertNull(InvestmentAdvicePlan.corePlan(portfolio, List.of(source), "เพิ่มเงินลงทุน"), "an already dominant core is not always the answer");
    }

    @Test
    void budgetsAndCurrenciesSumExactlyAndWeightsUseTheNewTotalCost() throws Exception {
        JsonNode portfolio = portfolio(mapper);
        var plan = InvestmentAdvicePlan.validate(mapper.readTree("""
                {"allocations":[{"symbol":"VTI","weight":80},{"symbol":"SCHD","weight":20}],
                "rationale":"เติมแกนพอร์ตที่กระจายกว้าง และรักษาส่วนปันผลให้พอดี", "avoid_symbols":["QQQM"],
                "sources":[]}
                """), portfolio, 0);
        assertEquals(new BigDecimal("4000.00"), InvestmentAdvicePlan.amounts(plan, new BigDecimal("5000")).get("VTI"));
        var dollars = InvestmentAdvicePlan.amounts(plan, new BigDecimal("148.65"));
        assertEquals(new BigDecimal("148.65"), dollars.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        var revised = InvestmentAdvicePlan.amounts(plan, new BigDecimal("3800"));
        assertEquals(new BigDecimal("3040.00"), revised.get("VTI"));
        assertEquals(new BigDecimal("760.00"), revised.get("SCHD"));
        var after = InvestmentAdvicePlan.projectedWeights(plan, portfolio, new BigDecimal("112.97"));
        assertTrue(after.get("QQQM").compareTo(new BigDecimal("20.38")) < 0, "unchanged QQQM is diluted by new capital");
        assertEquals(List.of("VTI", "SCHD"), InvestmentAdvicePlan.breaches(plan, portfolio, new BigDecimal("112.97")));
        String reply = InvestmentAdvicePlan.render(plan, portfolio, new InvestmentAdviceIntent.Budget(new BigDecimal("3800"), "THB"),
                mapper.readTree("""
                {"amount":3800,"converted_amount":112.97,"base_currency":"THB","quote_currency":"USD",
                 "rate":0.02973,"date":"2026-10-07","source":"test"}
                """), List.of(), false);
        assertTrue(reply.contains("3040.00 บาท"));
        assertTrue(reply.contains("760.00 บาท"));
        assertTrue(reply.contains("เกินเพดาน"));
        var previous = InvestmentAdvicePlan.previous(reply);
        assertEquals(InvestmentAdvicePlan.amounts(plan, new BigDecimal("3800")), InvestmentAdvicePlan.amounts(previous, new BigDecimal("3800")));
        String converted = InvestmentAdvicePlan.render(previous, portfolio,
                new InvestmentAdviceIntent.Budget(new BigDecimal("3800"), "THB"), mapper.readTree("""
                {"amount":3800,"converted_amount":112.97,"base_currency":"THB","quote_currency":"USD",
                 "rate":0.02973,"date":"2026-10-07","source":"test"}
                """), List.of(), true);
        assertTrue(converted.contains("รวม $112.97"));
        assertTrue(converted.contains("VTI"));
        assertFalse(converted.contains("→"), "a currency-only follow-up does not invent a new allocation review");
    }

    @Test
    void rejectsFabricatedSymbolsUnbalancedSplitsAndInventedFiguresInReasoning() throws Exception {
        String valid = """
                {"allocations":[{"symbol":"VTI","weight":100}],"rationale":"เสริมแกนพอร์ตให้กระจายกว้าง",
                 "avoid_symbols":["SCHD","QQQM"],"sources":[]}
                """;
        for (String invalid : List.of(valid.replace("\"weight\":100", "\"weight\":0"),
                valid.replace("\"symbol\":\"VTI\"", "\"symbol\":\"FAKE\""), valid.replace("กระจายกว้าง", "ขึ้นเป็น 99%"),
                valid.replace("กระจายกว้าง", "เสี่ยงต่ำ"), valid.replace("\"sources\":[]", "\"sources\":[0]"),
                valid.replace("\"avoid_symbols\":[\"SCHD\",\"QQQM\"]", "\"avoid_symbols\":[\"VTI\"]"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> InvestmentAdvicePlan.validate(mapper.readTree(invalid), portfolio(mapper), 0), invalid);
        }
        var plan = InvestmentAdvicePlan.validate(mapper.readTree(valid), portfolio(mapper), 0);
        String noFx = InvestmentAdvicePlan.render(plan, portfolio(mapper),
                new InvestmentAdviceIntent.Budget(new BigDecimal("1000"), "THB"), mapper.createObjectNode(), List.of(), false);
        assertTrue(noFx.contains("1000.00 บาท"));
        assertFalse(noFx.contains("~$"));
        assertFalse(noFx.contains("หลังเติมตามแผน"));
        String arrayDate = InvestmentAdvicePlan.render(plan, portfolio(mapper),
                new InvestmentAdviceIntent.Budget(new BigDecimal("1000"), "THB"), mapper.readTree("""
                {"amount":1000,"converted_amount":29.73,"base_currency":"THB","quote_currency":"USD",
                "rate":0.02973,"date":[2026,10,7],"source":"test"}
                """), List.of(), false);
        assertTrue(arrayDate.contains("วันที่ 2026-10-07"));
    }

    static JsonNode portfolio(ObjectMapper mapper) throws Exception {
        return mapper.readTree("""
                {"ownerId":"fixture","baseCurrency":"USD","allocationBasis":"AVERAGE_COST","totalOpenCostBasis":216.25008311338,
                 "policy":{"maxSinglePositionPercent":20,"goal":"","timeHorizon":"","riskTolerance":""},
                 "positions":[
                 {"symbol":"AMZN","instrumentName":"Amazon","assetClass":"EQUITY","costBasis":1.4100057712,"costAllocationPercent":0.652026},
                 {"symbol":"GIL","instrumentName":"Gildan","assetClass":"EQUITY","costBasis":34.98000828476,"costAllocationPercent":16.17572},
                 {"symbol":"GRAB","assetClass":"EQUITY","costBasis":1.5499998294,"costAllocationPercent":0.716763},
                 {"symbol":"LVS","assetClass":"EQUITY","costBasis":8.77999495563,"costAllocationPercent":4.060112},
                 {"symbol":"O","assetClass":"EQUITY","costBasis":35.2099899072,"costAllocationPercent":16.28207},
                 {"symbol":"QQQM","instrumentName":"Invesco Nasdaq 100 ETF","assetClass":"ETF","costBasis":44.07001320672,"costAllocationPercent":20.379189},
                 {"symbol":"SCHD","instrumentName":"Schwab US Dividend Equity ETF","assetClass":"ETF","costBasis":58.1900580079,"costAllocationPercent":26.908687},
                 {"symbol":"VTI","instrumentName":"Vanguard Total Stock Market ETF","assetClass":"ETF","costBasis":3.770013398,"costAllocationPercent":1.743358},
                 {"symbol":"WEC","assetClass":"EQUITY","costBasis":28.28999975257,"costAllocationPercent":13.082076}]}
                """);
    }
}
