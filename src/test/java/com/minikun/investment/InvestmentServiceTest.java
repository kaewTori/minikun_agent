package com.minikun.investment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.aop.framework.ProxyFactory;

class InvestmentServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-22T03:00:00Z");

    @Test
    void supportsClassBasedProxyingRequiredByTransactionalMethods() {
        ProxyFactory proxyFactory = new ProxyFactory(service(new InMemoryInvestmentStore()));
        proxyFactory.setProxyTargetClass(true);

        assertDoesNotThrow(() -> {
            proxyFactory.getProxy();
        });
    }

    @Test
    void calculatesAverageCostRealizedProfitAndPolicyWarningsWithoutMarketPrices() {
        InMemoryInvestmentStore store = new InMemoryInvestmentStore();
        InvestmentService service = service(store);
        service.setPolicy("owner-a", "THB", "SET TRI", decimal("60"));
        addTrade(service, "BUY", "AAA", "10", "100", "10", NOW.minusSeconds(500));
        addTrade(service, "BUY", "AAA", "10", "120", "10", NOW.minusSeconds(400));
        addTrade(service, "SELL", "AAA", "5", "150", "5", NOW.minusSeconds(300));
        addTrade(service, "BUY", "BBB", "5", "100", "0", NOW.minusSeconds(200));
        service.addTransaction("owner-a", "conversation-a", "broker", "DIVIDEND", "AAA", "Alpha",
                "EQUITY", "THB", null, null, decimal("100"), null, NOW.minusSeconds(100), "");

        PortfolioSummary summary = service.summary("owner-a");

        assertDecimal("2165", summary.totalOpenCostBasis());
        assertDecimal("190", summary.realizedProfitLoss());
        assertDecimal("100", summary.income());
        assertDecimal("25", summary.fees());
        assertEquals("AVERAGE_COST", summary.allocationBasis());
        PortfolioPosition aaa = summary.positions().stream()
                .filter(position -> position.symbol().equals("AAA")).findFirst().orElseThrow();
        assertDecimal("15", aaa.quantity());
        assertDecimal("1665", aaa.costBasis());
        assertDecimal("111", aaa.averageCost());
        assertTrue(summary.warnings().stream().anyMatch(warning -> warning.contains("AAA")));
    }

    @Test
    void enforcesOwnerCurrencyAndPreventsOverselling() {
        InvestmentService service = service(new InMemoryInvestmentStore());
        addTrade(service, "BUY", "AAA", "2", "100", "0", NOW.minusSeconds(10));

        assertThrows(IllegalArgumentException.class, () -> service.addTransaction(
                "owner-a", "conversation-a", "broker", "SELL", "AAA", "Alpha", "EQUITY", "THB",
                decimal("3"), decimal("100"), null, null, NOW, ""));
        assertThrows(IllegalArgumentException.class, () -> service.addTransaction(
                "owner-a", "conversation-a", "broker", "BUY", "USD1", "US Asset", "EQUITY", "USD",
                decimal("1"), decimal("10"), null, null, NOW, ""));
        assertThrows(IllegalArgumentException.class,
                () -> service.setPolicy("owner-a", "USD", "", decimal("20")));
        assertTrue(service.summary("owner-b").positions().isEmpty());
    }

    @Test
    void simulatesBuyAgainstCostBasisAndCanVoidMistakenEntry() {
        InMemoryInvestmentStore store = new InMemoryInvestmentStore();
        InvestmentService service = service(store);
        addTrade(service, "BUY", "AAA", "10", "100", "0", NOW.minusSeconds(10));
        InvestmentTransaction mistaken = service.addTransaction(
                "owner-a", "conversation-a", "broker", "BUY", "BBB", "Beta", "ETF", "THB",
                decimal("5"), decimal("100"), null, null, NOW, "mistake");

        Map<String, Object> simulation = service.simulateBuy("owner-a", "AAA", decimal("500"));
        assertDecimal("1500", (BigDecimal) simulation.get("projected_symbol_cost"));
        assertDecimal("2000", (BigDecimal) simulation.get("projected_total_cost"));
        assertDecimal("75", (BigDecimal) simulation.get("projected_cost_allocation_percent"));

        assertTrue(service.voidTransaction("owner-a", mistaken.id()));
        assertEquals(List.of("AAA"), service.summary("owner-a").positions().stream()
                .map(PortfolioPosition::symbol).toList());
        assertTrue(service.transactions("owner-a").stream()
                .filter(transaction -> transaction.id().equals(mistaken.id()))
                .findFirst().orElseThrow().voidedAt() != null);
    }

    @Test
    void refusesToVoidABuyThatWouldExposeHistoricalOversell() {
        InMemoryInvestmentStore store = new InMemoryInvestmentStore();
        InvestmentService service = service(store);
        InvestmentTransaction buy = service.addTransaction(
                "owner-a", "conversation-a", "broker", "BUY", "AAA", "Alpha", "EQUITY", "THB",
                decimal("2"), decimal("100"), null, null, NOW.minusSeconds(10), "");
        service.addTransaction(
                "owner-a", "conversation-a", "broker", "SELL", "AAA", "Alpha", "EQUITY", "THB",
                decimal("1"), decimal("120"), null, null, NOW, "");

        assertThrows(IllegalArgumentException.class, () -> service.voidTransaction("owner-a", buy.id()));
        assertTrue(service.transactions("owner-a").stream()
                .filter(transaction -> transaction.id().equals(buy.id()))
                .findFirst().orElseThrow().active());
    }

    @Test
    void storesAndClosesOwnerScopedInvestmentThesis() {
        InvestmentService service = service(new InMemoryInvestmentStore());
        InvestmentThesis thesis = service.saveThesis(
                "owner-a", "conversation-a", null, "AAA", "กำไรเติบโตโดยไม่เพิ่มหนี้",
                "หนี้สุทธิเกินสองเท่าของ EBITDA", NOW.plusSeconds(86_400));

        assertEquals(1, service.theses("owner-a", InvestmentThesisStatus.ACTIVE).size());
        assertTrue(service.theses("owner-b", null).isEmpty());
        InvestmentThesis closed = service.closeThesis("owner-a", thesis.id());
        assertEquals(InvestmentThesisStatus.CLOSED, closed.status());
        assertTrue(closed.closedAt() != null);
    }

    private InvestmentService service(InMemoryInvestmentStore store) {
        return new InvestmentService(store, Clock.fixed(NOW, ZoneOffset.UTC), "THB");
    }

    private void addTrade(
            InvestmentService service, String type, String symbol, String quantity, String price, String fee,
            Instant occurredAt) {
        service.addTransaction("owner-a", "conversation-a", "broker", type, symbol, symbol + " name", "EQUITY",
                "THB", decimal(quantity), decimal(price), null, decimal(fee), occurredAt, "");
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but got " + actual);
    }

    static final class InMemoryInvestmentStore implements InvestmentStore {
        private final Map<String, InvestmentPolicy> policies = new HashMap<>();
        private final List<InvestmentTransaction> transactions = new ArrayList<>();
        private final List<InvestmentThesis> theses = new ArrayList<>();

        @Override
        public Optional<InvestmentPolicy> findPolicy(String ownerId) {
            return Optional.ofNullable(policies.get(ownerId));
        }

        @Override
        public InvestmentPolicy savePolicy(InvestmentPolicy policy) {
            policies.put(policy.ownerId(), policy);
            return policy;
        }

        @Override
        public InvestmentTransaction addTransaction(InvestmentTransaction transaction) {
            transactions.add(transaction);
            return transaction;
        }

        @Override
        public List<InvestmentTransaction> listTransactions(String ownerId) {
            return transactions.stream().filter(value -> value.ownerId().equals(ownerId)).toList();
        }

        @Override
        public boolean voidTransaction(UUID id, String ownerId, Instant voidedAt) {
            for (int index = 0; index < transactions.size(); index++) {
                InvestmentTransaction value = transactions.get(index);
                if (value.id().equals(id) && value.ownerId().equals(ownerId) && value.active()) {
                    transactions.set(index, new InvestmentTransaction(
                            value.id(), value.ownerId(), value.conversationId(), value.account(), value.type(),
                            value.symbol(), value.instrumentName(), value.assetClass(), value.currency(),
                            value.quantity(), value.unitPrice(), value.amount(), value.fee(), value.occurredAt(),
                            value.note(), value.createdAt(), voidedAt));
                    return true;
                }
            }
            return false;
        }

        @Override
        public Optional<InvestmentThesis> findThesis(UUID id, String ownerId) {
            return theses.stream().filter(value -> value.id().equals(id) && value.ownerId().equals(ownerId))
                    .findFirst();
        }

        @Override
        public InvestmentThesis saveThesis(InvestmentThesis thesis) {
            theses.removeIf(value -> value.id().equals(thesis.id()) && value.ownerId().equals(thesis.ownerId()));
            theses.add(thesis);
            return thesis;
        }

        @Override
        public List<InvestmentThesis> listTheses(String ownerId, InvestmentThesisStatus status) {
            return theses.stream().filter(value -> value.ownerId().equals(ownerId))
                    .filter(value -> status == null || value.status() == status).toList();
        }
    }
}
