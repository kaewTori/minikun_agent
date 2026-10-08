package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.investment.InvestmentPolicy;
import com.minikun.investment.InvestmentQuotePriority;
import com.minikun.investment.InvestmentService;
import com.minikun.investment.InvestmentStore;
import com.minikun.investment.InvestmentThesis;
import com.minikun.investment.InvestmentThesisStatus;
import com.minikun.investment.InvestmentTransaction;
import com.minikun.planner.PendingPlannerConfirmation;
import com.minikun.planner.PlannerConfirmationService;
import com.minikun.planner.PlannerConfirmationStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvestmentConfirmationRouterTest {
    @Test
    void recordsTheReportedFractionalBuysAsOneConfirmedBatchWithoutFetchingMarketPrices() {
        InMemoryInvestmentStore store = new InMemoryInvestmentStore();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InvestmentService service = new InvestmentService(store, clock, "USD");
        service.addTransaction("owner-a", "opening", "broker", "BUY", "SCHD", "Schwab", "ETF", "USD",
                BigDecimal.ONE, BigDecimal.TEN, null, null, NOW.minusSeconds(10), "");
        service.addTransaction("owner-a", "opening", "broker", "BUY", "VTI", "Vanguard", "ETF", "USD",
                BigDecimal.ONE, BigDecimal.TEN, null, null, NOW.minusSeconds(10), "");
        var confirmations = new PlannerConfirmationService(new InMemoryConfirmationStore(), clock);
        var router = new InvestmentTradeReportRouter(service, confirmations, new ObjectMapper(), clock);
        String report = """
                อัพเดท port ให้เราตามนี้หน่อย
                ซื้อ SCHD 29.37$ ในราคา 32.80$ ต่อหน่วยได้ 0.8939024 หน่วย
                ซื้อ VTI 89.01$ ในราคา 380.60$ ต่อหน่วยได้ 0.2338675 หน่วย
                """;
        ToolExecutor noMarketReads = (context, call) -> { throw new AssertionError("update must not fetch quotes"); };
        var id = new ConversationId("fractional-buys");
        assertTrue(new InvestmentMarketRouter(noMarketReads, new ObjectMapper(), service).route(report, id, "owner-a").isEmpty());
        assertTrue(new InvestmentMonitorRouter(noMarketReads, new ObjectMapper()).route(report, id, "owner-a").isEmpty());
        assertTrue(new InvestmentReviewRouter(noMarketReads, new ObjectMapper()).route(report, id, "owner-a").isEmpty());
        var proposal = router.route(report, id, "owner-a").orElseThrow();
        assertTrue(proposal.requiresConfirmation(), proposal.content());
        assertTrue(proposal.content().contains("ส่วนต่าง"));
        assertEquals(2, store.transactions.size());
        assertTrue(router.route("ยืนยัน", id, "owner-b").isEmpty());
        var result = router.route("ยืนยัน", id, "owner-a").orElseThrow();
        assertTrue(result.success(), result.content());
        assertEquals(4, store.transactions.size());
        var buys = store.transactions.subList(2, 4);
        assertEquals(List.of("SCHD", "VTI"), buys.stream().map(InvestmentTransaction::symbol).toList());
        assertTrue(buys.stream().allMatch(trade -> trade.type() == com.minikun.investment.InvestmentTransactionType.BUY));
        assertEquals(0, new BigDecimal("0.8939024").compareTo(buys.getFirst().quantity()));
        assertEquals(0, new BigDecimal("0.2338675").compareTo(buys.getLast().quantity()));
        assertEquals(0, new BigDecimal("0.050001280").compareTo(buys.getFirst().fee()));
        assertEquals(0, new BigDecimal("138.38").compareTo(service.summary("owner-a").totalOpenCostBasis()));
        assertTrue(confirmations.find(id, "owner-a").isEmpty());
        assertTrue(!router.route(report, id, "owner-a").orElseThrow().success());
        assertEquals(4, store.transactions.size());
    }

    @Test
    void rejectsAnIncompleteOrInconsistentBuyBatchBeforeSavingAProposal() {
        var store = new InMemoryInvestmentStore();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var service = new InvestmentService(store, clock, "USD");
        service.addTransaction("owner-a", "opening", "broker", "BUY", "VTI", "Vanguard", "ETF", "USD",
                BigDecimal.ONE, BigDecimal.TEN, null, null, NOW.minusSeconds(10), "");
        var confirmations = new PlannerConfirmationService(new InMemoryConfirmationStore(), clock);
        var router = new InvestmentTradeReportRouter(service, confirmations, new ObjectMapper(), clock);
        var id = new ConversationId("invalid-buys");
        String first = "อัพเดท port\nซื้อ SCHD 29.37$ ในราคา 32.80$ ต่อหน่วยได้ 0.8939024 หน่วย\n";
        for (String second : List.of("ซื้อ VTI 80.00$ ในราคา 380.60$ ต่อหน่วยได้ 0.2338675 หน่วย",
                "ซื้อ VTI 89.01$ ในราคา 380.60$ ต่อหน่วย", "ซื้อ VTI 89.01 THB ในราคา 380.60$ ต่อหน่วยได้ 0.2338675 หน่วย")) {
            assertTrue(!router.route(first + second, id, "owner-a").orElseThrow().success());
            assertTrue(confirmations.find(id, "owner-a").isEmpty());
            assertEquals(1, store.transactions.size());
        }
        assertTrue(router.route("ถ้าซื้อตามนี้จะดีไหม\nซื้อ VTI 89.01$ ในราคา 380.60$ ต่อหน่วยได้ 0.2338675 หน่วย",
                id, "owner-a").isEmpty());
    }

    private static final Instant NOW = Instant.parse("2026-08-22T03:00:00Z");

    @Test
    void replaysExactOwnerScopedTransactionOnlyAfterConfirmation() {
        InMemoryInvestmentStore investments = new InMemoryInvestmentStore();
        InMemoryConfirmationStore pending = new InMemoryConfirmationStore();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InvestmentService service = new InvestmentService(investments, clock, "THB");
        PlannerConfirmationService confirmations = new PlannerConfirmationService(pending, clock);
        InvestmentManageTool tool = new InvestmentManageTool(service, confirmations);
        ToolExecutor executor = new DefaultToolExecutor(new DefaultToolRegistry(List.of(tool)));
        InvestmentConfirmationRouter router = new InvestmentConfirmationRouter(executor, confirmations);
        ConversationId conversation = new ConversationId("investment-confirmation");

        ToolResult proposal = tool.execute(new ToolCallContext(conversation, "proposal", "owner-a"), Map.of(
                "action", "add_transaction", "account", "broker", "type", "BUY", "symbol", "AAA",
                "asset_class", "EQUITY", "currency", "THB", "quantity", 10, "unit_price", 100));

        assertTrue(proposal.success());
        assertTrue(investments.transactions.isEmpty());
        assertTrue(confirmations.find(conversation, "owner-a").isPresent());
        assertTrue(router.route("ยืนยันครับ", conversation, "owner-b").isEmpty());

        Optional<ToolEvidence> evidence = router.route("ยืนยันครับ", conversation, "owner-a");

        assertTrue(evidence.isPresent());
        assertTrue(evidence.get().success());
        assertTrue(evidence.get().finalResponse());
        assertEquals(1, investments.transactions.size());
        assertEquals("owner-a", investments.transactions.getFirst().ownerId());
        assertEquals("AAA", investments.transactions.getFirst().symbol());
        assertTrue(confirmations.find(conversation, "owner-a").isEmpty());
    }

    @Test
    void rejectsIncompleteTradeBeforeCreatingPendingConfirmation() {
        InMemoryInvestmentStore investments = new InMemoryInvestmentStore();
        InMemoryConfirmationStore pending = new InMemoryConfirmationStore();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PlannerConfirmationService confirmations = new PlannerConfirmationService(pending, clock);
        InvestmentManageTool tool = new InvestmentManageTool(
                new InvestmentService(investments, clock, "USD"), confirmations);

        ToolResult result = tool.execute(new ToolCallContext(
                new ConversationId("incomplete-trade"), "proposal", "owner-a"), Map.of(
                        "action", "add_transaction", "type", "SELL", "symbol", "WHR",
                        "currency", "USD", "quantity", 1, "unit_price", 35.13));

        assertTrue(!result.success());
        assertTrue(result.error().contains("account"));
        assertTrue(confirmations.find(new ConversationId("incomplete-trade"), "owner-a").isEmpty());
    }

    @Test
    void derivesSellFeeFromNetProceeds() {
        InMemoryInvestmentStore investments = new InMemoryInvestmentStore();
        InMemoryConfirmationStore pending = new InMemoryConfirmationStore();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InvestmentService service = new InvestmentService(investments, clock, "USD");
        service.addTransaction("owner-a", "opening", "broker", "BUY", "AAA", "Alpha",
                "EQUITY", "USD", BigDecimal.TEN, BigDecimal.valueOf(100), null, null, NOW, "");
        PlannerConfirmationService confirmations = new PlannerConfirmationService(pending, clock);
        InvestmentManageTool tool = new InvestmentManageTool(service, confirmations);
        InvestmentConfirmationRouter router = new InvestmentConfirmationRouter(
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(tool))), confirmations);
        ConversationId conversation = new ConversationId("net-proceeds");

        ToolResult proposal = tool.execute(new ToolCallContext(conversation, "proposal", "owner-a"), Map.of(
                "action", "add_transaction", "account", "broker", "type", "SELL", "symbol", "AAA",
                "currency", "USD", "quantity", 10, "unit_price", 50, "net_proceeds", 490));

        assertTrue(proposal.success());
        assertTrue(router.route("ยืนยัน", conversation, "owner-a").isPresent());
        assertEquals(0, BigDecimal.TEN.compareTo(investments.transactions.getLast().fee()));
    }

    @Test
    void fillsLegacyMissingAccountFromTheOnlyExistingLedgerAccount() {
        InMemoryInvestmentStore investments = new InMemoryInvestmentStore();
        InMemoryConfirmationStore pending = new InMemoryConfirmationStore();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InvestmentService service = new InvestmentService(investments, clock, "USD");
        service.addTransaction("owner-a", "opening", "us-long-term", "BUY", "WHR", "Whirlpool",
                "EQUITY", "USD", BigDecimal.valueOf(0.0277945), BigDecimal.valueOf(132.76), null, null,
                NOW, "");
        PlannerConfirmationService confirmations = new PlannerConfirmationService(pending, clock);
        ConversationId conversation = new ConversationId("legacy-confirmation");
        confirmations.save(conversation, "owner-a", "investment.add_transaction", Map.of(
                "action", "add_transaction", "type", "SELL", "symbol", "WHR", "currency", "USD",
                "quantity", BigDecimal.valueOf(0.0277945), "unit_price", BigDecimal.valueOf(35.13)));
        InvestmentManageTool tool = new InvestmentManageTool(service, confirmations);
        InvestmentConfirmationRouter router = new InvestmentConfirmationRouter(
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(tool))), confirmations);

        Optional<ToolEvidence> evidence = router.route("ยืนยัน", conversation, "owner-a");

        assertTrue(evidence.isPresent());
        assertTrue(evidence.get().success());
        assertEquals("us-long-term", investments.transactions.getLast().account());
    }

    @Test
    void turnsFailedConfirmationIntoFinalEvidence() {
        InMemoryConfirmationStore pending = new InMemoryConfirmationStore();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PlannerConfirmationService confirmations = new PlannerConfirmationService(pending, clock);
        ConversationId conversation = new ConversationId("failed-confirmation");
        confirmations.save(conversation, "owner-a", "investment.add_transaction", Map.of(
                "action", "add_transaction", "account", "broker", "type", "SELL", "symbol", "WHR"));
        ToolExecutor executor = (context, call) -> ToolResult.failure(
                ToolErrorCode.INVALID_ARGUMENTS, "account is required for add_transaction");
        InvestmentConfirmationRouter router = new InvestmentConfirmationRouter(executor, confirmations);

        Optional<ToolEvidence> evidence = router.route("ยืนยัน", conversation, "owner-a");

        assertTrue(evidence.isPresent());
        assertTrue(!evidence.get().success());
        assertTrue(evidence.get().finalResponse());
        assertTrue(evidence.get().content().contains("account is required"));
        assertTrue(confirmations.find(conversation, "owner-a").isPresent());
    }

    @Test
    void recordsAllOwnerReportedSalesUsingCurrentLedgerQuantities() {
        InMemoryInvestmentStore investments = new InMemoryInvestmentStore();
        InMemoryConfirmationStore pending = new InMemoryConfirmationStore();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InvestmentService service = new InvestmentService(investments, clock, "USD");
        service.addTransaction("owner-a", "opening", "us-long-term", "BUY", "WHR", "Whirlpool",
                "EQUITY", "USD", BigDecimal.valueOf(0.0277945), BigDecimal.valueOf(132.76), null, null,
                NOW.minusSeconds(10), "");
        service.addTransaction("owner-a", "opening", "us-long-term", "BUY", "SPOT", "Spotify",
                "EQUITY", "USD", BigDecimal.valueOf(0.0019670), BigDecimal.valueOf(721.8980), null, null,
                NOW.minusSeconds(10), "");
        PlannerConfirmationService confirmations = new PlannerConfirmationService(pending, clock);
        ConversationId conversation = new ConversationId("reported-sales");
        InvestmentTradeReportRouter router = new InvestmentTradeReportRouter(
                service, confirmations, new ObjectMapper(), clock);

        Optional<ToolEvidence> proposal = router.route("""
                WHR 35.13USD หลังหักค่าธรรมเนียมแล้วได้มา 0.95 USD
                SPOT 523.37 หลังหักค่าธรรมเนียมได้มา 1.01 USD
                """, conversation, "owner-a");

        assertTrue(proposal.isPresent());
        assertTrue(proposal.get().requiresConfirmation(), proposal.get().content());
        assertEquals(2, ((List<?>) pending.value.arguments().get("transactions")).size());
        assertEquals(2, service.summary("owner-a").positions().size());

        Optional<ToolEvidence> result = router.route("ยืนยัน", conversation, "owner-a");

        assertTrue(result.isPresent());
        assertTrue(result.get().success(), result.get().content());
        assertTrue(result.get().finalResponse());
        assertEquals(4, investments.transactions.size());
        assertEquals("WHR", investments.transactions.get(2).symbol());
        assertEquals(0, BigDecimal.valueOf(0.0277945).compareTo(investments.transactions.get(2).quantity()));
        assertEquals(0, BigDecimal.valueOf(0.0277945).multiply(BigDecimal.valueOf(35.13))
                .subtract(BigDecimal.valueOf(0.95)).compareTo(investments.transactions.get(2).fee()));
        assertEquals("SPOT", investments.transactions.get(3).symbol());
        assertEquals(0, BigDecimal.valueOf(0.0019670).compareTo(investments.transactions.get(3).quantity()));
        assertTrue(service.summary("owner-a").positions().isEmpty());
    }

    @Test
    void recordsPartialSaleOnReportedDateAndRejectsDuplicateReport() {
        InMemoryInvestmentStore investments = new InMemoryInvestmentStore();
        InMemoryConfirmationStore pending = new InMemoryConfirmationStore();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InvestmentService service = new InvestmentService(investments, clock, "USD");
        service.addTransaction("owner-a", "opening", "us-long-term", "BUY", "AAA", "Alpha",
                "EQUITY", "USD", BigDecimal.TEN, BigDecimal.valueOf(100), null, null,
                Instant.parse("2026-08-21T10:00:00Z"), "");
        PlannerConfirmationService confirmations = new PlannerConfirmationService(pending, clock);
        ConversationId conversation = new ConversationId("partial-sale");
        InvestmentTradeReportRouter router = new InvestmentTradeReportRouter(
                service, confirmations, new ObjectMapper(), clock);
        String report = """
                วันที่ 2026-08-22
                AAA 2 หุ้น @ 35.13 USD หลังหักค่าธรรมเนียมแล้วได้มา 69 USD
                """;

        Optional<ToolEvidence> proposal = router.route(report, conversation, "owner-a");

        assertTrue(proposal.isPresent());
        assertTrue(proposal.get().requiresConfirmation());
        Map<?, ?> row = (Map<?, ?>) ((List<?>) pending.value.arguments().get("transactions")).getFirst();
        assertEquals(0, BigDecimal.valueOf(2).compareTo((BigDecimal) row.get("quantity")));
        assertEquals("2026-08-22T23:59:59Z", row.get("occurred_at"));

        Optional<ToolEvidence> result = router.route("ยืนยัน", conversation, "owner-a");

        assertTrue(result.isPresent());
        assertTrue(result.get().success(), result.get().content());
        assertEquals(2, investments.transactions.size());
        assertEquals(0, BigDecimal.valueOf(2).compareTo(investments.transactions.getLast().quantity()));
        assertEquals(0, new BigDecimal("1.26").compareTo(investments.transactions.getLast().fee()));
        assertEquals(0, BigDecimal.valueOf(8).compareTo(service.summary("owner-a").positions().getFirst().quantity()));

        Optional<ToolEvidence> duplicate = router.route(report, new ConversationId("duplicate"), "owner-a");

        assertTrue(duplicate.isPresent());
        assertTrue(!duplicate.get().success());
        assertTrue(duplicate.get().content().contains("บันทึกไปแล้ว"));
        assertEquals(2, investments.transactions.size());
    }

    @Test
    void rejectsConfirmedFlagWithoutMatchingDurableProposal() {
        InMemoryInvestmentStore investments = new InMemoryInvestmentStore();
        InMemoryConfirmationStore pending = new InMemoryConfirmationStore();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InvestmentManageTool tool = new InvestmentManageTool(
                new InvestmentService(investments, clock, "THB"),
                new PlannerConfirmationService(pending, clock));

        ToolResult result = tool.execute(
                new ToolCallContext(new ConversationId("no-proposal"), "call", "owner-a"),
                Map.of("action", "set_policy", "base_currency", "THB", "confirmed", true));

        assertTrue(!result.success());
        assertTrue(investments.policy == null);
    }

    @Test
    void persistsQuotePriorityOnlyAfterConfirmation() {
        InMemoryInvestmentStore investments = new InMemoryInvestmentStore();
        InMemoryConfirmationStore pending = new InMemoryConfirmationStore();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InvestmentService service = new InvestmentService(investments, clock, "THB");
        service.addTransaction("owner-a", "conversation-a", "broker", "BUY", "AAA", "Alpha",
                "EQUITY", "THB", BigDecimal.ONE, BigDecimal.valueOf(100), null, null, NOW, "");
        service.addTransaction("owner-a", "conversation-a", "broker", "BUY", "BBB", "Beta",
                "EQUITY", "THB", BigDecimal.ONE, BigDecimal.valueOf(100), null, null, NOW, "");
        PlannerConfirmationService confirmations = new PlannerConfirmationService(pending, clock);
        InvestmentManageTool tool = new InvestmentManageTool(service, confirmations);
        ToolExecutor executor = new DefaultToolExecutor(new DefaultToolRegistry(List.of(tool)));
        InvestmentConfirmationRouter router = new InvestmentConfirmationRouter(executor, confirmations);
        ConversationId conversation = new ConversationId("quote-priority-confirmation");

        ToolResult proposal = tool.execute(new ToolCallContext(conversation, "proposal", "owner-a"), Map.of(
                "action", "set_quote_priority", "symbols", "AAA,BBB", "priority", "MINOR"));

        assertTrue(proposal.success());
        assertTrue(tool.requiresExplicitConfirmation(Map.of("action", "set_quote_priority")));
        assertTrue(investments.quotePriorities.isEmpty());
        assertTrue(router.route("ยืนยันครับ", conversation, "owner-a").isPresent());
        assertEquals(InvestmentQuotePriority.MINOR, investments.quotePriorities.get("AAA"));
        assertEquals(InvestmentQuotePriority.MINOR, investments.quotePriorities.get("BBB"));
    }

    private static final class InMemoryConfirmationStore implements PlannerConfirmationStore {
        private PendingPlannerConfirmation value;

        @Override public void save(PendingPlannerConfirmation confirmation) { value = confirmation; }
        @Override public Optional<PendingPlannerConfirmation> find(String conversationId, Instant now) {
            return value != null && value.conversationId().equals(conversationId) && value.expiresAt().isAfter(now)
                    ? Optional.of(value) : Optional.empty();
        }
        @Override public void clear(String conversationId) {
            if (value != null && value.conversationId().equals(conversationId)) value = null;
        }
    }

    private static final class InMemoryInvestmentStore implements InvestmentStore {
        private InvestmentPolicy policy;
        private final Map<String, InvestmentQuotePriority> quotePriorities = new HashMap<>();
        private final List<InvestmentTransaction> transactions = new ArrayList<>();
        private final List<InvestmentThesis> theses = new ArrayList<>();

        @Override public Optional<InvestmentPolicy> findPolicy(String ownerId) {
            return policy != null && policy.ownerId().equals(ownerId) ? Optional.of(policy) : Optional.empty();
        }
        @Override public InvestmentPolicy savePolicy(InvestmentPolicy value) { policy = value; return value; }
        @Override public InvestmentTransaction addTransaction(InvestmentTransaction value) {
            transactions.add(value); return value;
        }
        @Override public List<InvestmentTransaction> listTransactions(String ownerId) {
            return transactions.stream().filter(value -> value.ownerId().equals(ownerId)).toList();
        }
        @Override public boolean voidTransaction(UUID id, String ownerId, Instant voidedAt) { return false; }
        @Override public Optional<InvestmentThesis> findThesis(UUID id, String ownerId) { return Optional.empty(); }
        @Override public InvestmentThesis saveThesis(InvestmentThesis value) { theses.add(value); return value; }
        @Override public List<InvestmentThesis> listTheses(String ownerId, InvestmentThesisStatus status) {
            return List.of();
        }
        @Override public Map<String, InvestmentQuotePriority> listQuotePriorities(String ownerId) {
            return new HashMap<>(quotePriorities);
        }
        @Override public void saveQuotePriority(
                String ownerId, String symbol, InvestmentQuotePriority priority, Instant updatedAt) {
            quotePriorities.put(symbol, priority);
        }
    }
}
