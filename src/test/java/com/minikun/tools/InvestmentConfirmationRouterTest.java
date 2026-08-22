package com.minikun.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.investment.InvestmentPolicy;
import com.minikun.investment.InvestmentService;
import com.minikun.investment.InvestmentStore;
import com.minikun.investment.InvestmentThesis;
import com.minikun.investment.InvestmentThesisStatus;
import com.minikun.investment.InvestmentTransaction;
import com.minikun.planner.PendingPlannerConfirmation;
import com.minikun.planner.PlannerConfirmationService;
import com.minikun.planner.PlannerConfirmationStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvestmentConfirmationRouterTest {
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
        assertEquals(1, investments.transactions.size());
        assertEquals("owner-a", investments.transactions.getFirst().ownerId());
        assertEquals("AAA", investments.transactions.getFirst().symbol());
        assertTrue(confirmations.find(conversation, "owner-a").isEmpty());
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
    }
}
