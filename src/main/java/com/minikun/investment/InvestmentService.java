package com.minikun.investment;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic investment ledger, policy, journal, and cost-basis analysis. */
@Service
@ConditionalOnProperty(name = "minikun.investment.enabled", havingValue = "true", matchIfMissing = true)
public class InvestmentService {
    private static final MathContext MATH = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public record TransactionInput(
            String account,
            String type,
            String symbol,
            String instrumentName,
            String assetClass,
            String currency,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal amount,
            BigDecimal fee,
            Instant occurredAt,
            String note) {
    }

    private final InvestmentStore store;
    private final Clock clock;
    private final String defaultBaseCurrency;

    public InvestmentService(
            InvestmentStore store,
            Clock clock,
            @Value("${minikun.investment.default-base-currency:THB}") String defaultBaseCurrency) {
        this.store = Objects.requireNonNull(store, "investment store must not be null");
        this.clock = Objects.requireNonNull(clock, "investment clock must not be null");
        this.defaultBaseCurrency = InvestmentPolicy.requireCurrency(defaultBaseCurrency);
    }

    public InvestmentPolicy policy(String ownerId) {
        String owner = InvestmentPolicy.requireOwner(ownerId);
        return store.findPolicy(owner).orElseGet(() -> defaultPolicy(owner));
    }

    @Transactional
    public InvestmentPolicy setPolicy(
            String ownerId, String baseCurrency, String benchmark, BigDecimal maxSinglePositionPercent) {
        return setPolicy(ownerId, baseCurrency, benchmark, maxSinglePositionPercent, null, null, null);
    }

    @Transactional
    public InvestmentPolicy setPolicy(
            String ownerId,
            String baseCurrency,
            String benchmark,
            BigDecimal maxSinglePositionPercent,
            String goal,
            String timeHorizon,
            String riskTolerance) {
        String owner = InvestmentPolicy.requireOwner(ownerId);
        Instant now = clock.instant();
        Optional<InvestmentPolicy> existing = store.findPolicy(owner);
        InvestmentPolicy current = existing.orElseGet(() -> defaultPolicy(owner));
        InvestmentPolicy policy = new InvestmentPolicy(
                owner,
                baseCurrency == null || baseCurrency.isBlank()
                        ? existing.map(InvestmentPolicy::baseCurrency).orElse(defaultBaseCurrency)
                        : baseCurrency,
                benchmark == null
                        ? existing.map(InvestmentPolicy::benchmark).orElse("")
                        : benchmark,
                maxSinglePositionPercent == null
                        ? existing.map(InvestmentPolicy::maxSinglePositionPercent).orElse(new BigDecimal("20"))
                        : maxSinglePositionPercent,
                goal == null ? current.goal() : goal,
                timeHorizon == null ? current.timeHorizon() : timeHorizon,
                riskTolerance == null ? current.riskTolerance() : riskTolerance,
                existing.map(InvestmentPolicy::createdAt).orElse(now),
                now);
        if (!store.listTransactions(owner).isEmpty()
                && !current.baseCurrency().equals(policy.baseCurrency())) {
            throw new IllegalArgumentException("base currency cannot change after investment transactions exist");
        }
        return store.savePolicy(policy);
    }

    @Transactional
    public InvestmentTransaction addTransaction(
            String ownerId,
            String conversationId,
            String account,
            String type,
            String symbol,
            String instrumentName,
            String assetClass,
            String currency,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal amount,
            BigDecimal fee,
            Instant occurredAt,
            String note) {
        String owner = InvestmentPolicy.requireOwner(ownerId);
        InvestmentPolicy policy = policy(owner);
        String normalizedCurrency = currency == null || currency.isBlank() ? policy.baseCurrency() : currency;
        if (!InvestmentPolicy.requireCurrency(normalizedCurrency).equals(policy.baseCurrency())) {
            throw new IllegalArgumentException(
                    "phase-one investment ledger only accepts the portfolio base currency " + policy.baseCurrency());
        }
        InvestmentTransaction transaction = new InvestmentTransaction(
                UUID.randomUUID(), owner, conversationId, account,
                InvestmentTransactionType.parse(type), symbol, instrumentName, assetClass,
                normalizedCurrency, quantity, unitPrice, amount, fee,
                occurredAt == null ? clock.instant() : occurredAt, note, clock.instant(), null);
        if (transaction.type() == InvestmentTransactionType.SELL) {
            BigDecimal held = summary(owner).positions().stream()
                    .filter(position -> position.symbol().equals(transaction.symbol()))
                    .map(PortfolioPosition::quantity)
                    .findFirst().orElse(BigDecimal.ZERO);
            if (held.compareTo(transaction.quantity()) < 0) {
                throw new IllegalArgumentException("SELL quantity exceeds the currently recorded holding of "
                        + plain(held) + " " + transaction.symbol());
            }
        }
        return store.addTransaction(transaction);
    }

    /** Persists a reported group of transactions as one ledger operation. */
    @Transactional
    public List<InvestmentTransaction> addTransactions(
            String ownerId, String conversationId, List<TransactionInput> inputs) {
        return addTransactions(ownerId, conversationId, inputs, null);
    }

    @Transactional
    public List<InvestmentTransaction> addTransactions(
            String ownerId,
            String conversationId,
            List<TransactionInput> inputs,
            String sourceFingerprint) {
        String owner = InvestmentPolicy.requireOwner(ownerId);
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("at least one investment transaction is required");
        }
        // ponytail: note scan keeps this migration-free; add an indexed fingerprint column if ledger volume demands it.
        if (sourceFingerprint != null && !sourceFingerprint.isBlank()
                && store.listTransactions(owner).stream()
                        .filter(InvestmentTransaction::active)
                        .anyMatch(transaction -> transaction.note().contains(
                                "report_fingerprint=" + sourceFingerprint))) {
            throw new IllegalArgumentException("รายการรายงานนี้ถูกบันทึกไปแล้วใน ledger");
        }
        List<InvestmentTransaction> saved = new ArrayList<>();
        for (TransactionInput input : inputs) {
            if (input == null) throw new IllegalArgumentException("investment transaction must not be null");
            saved.add(addTransaction(
                    owner, conversationId, input.account(), input.type(), input.symbol(), input.instrumentName(),
                    input.assetClass(), input.currency(), input.quantity(), input.unitPrice(), input.amount(),
                    input.fee(), input.occurredAt(), input.note()));
        }
        return List.copyOf(saved);
    }

    @Transactional
    public boolean voidTransaction(String ownerId, UUID transactionId) {
        String owner = InvestmentPolicy.requireOwner(ownerId);
        Objects.requireNonNull(transactionId, "investment transaction id must not be null");
        List<InvestmentTransaction> ledger = new ArrayList<>(store.listTransactions(owner));
        boolean exists = ledger.stream().anyMatch(transaction -> transaction.id().equals(transactionId)
                && transaction.active());
        if (!exists) {
            throw new IllegalArgumentException("active investment transaction was not found for this owner");
        }
        validateTradeSequence(ledger.stream()
                .filter(transaction -> !transaction.id().equals(transactionId))
                .filter(InvestmentTransaction::active)
                .toList());
        boolean changed = store.voidTransaction(transactionId, owner, clock.instant());
        if (!changed) {
            throw new IllegalArgumentException("active investment transaction was not found for this owner");
        }
        return true;
    }

    public List<InvestmentTransaction> transactions(String ownerId) {
        return List.copyOf(store.listTransactions(InvestmentPolicy.requireOwner(ownerId)));
    }

    @Transactional
    public InvestmentThesis saveThesis(
            String ownerId,
            String conversationId,
            UUID id,
            String symbol,
            String summary,
            String invalidation,
            Instant nextReviewAt) {
        String owner = InvestmentPolicy.requireOwner(ownerId);
        Instant now = clock.instant();
        Optional<InvestmentThesis> existing = id == null ? Optional.empty() : store.findThesis(id, owner);
        if (id != null && existing.isEmpty()) {
            throw new IllegalArgumentException("investment thesis was not found for this owner");
        }
        InvestmentThesis thesis = new InvestmentThesis(
                existing.map(InvestmentThesis::id).orElseGet(UUID::randomUUID),
                owner,
                conversationId,
                symbol == null || symbol.isBlank()
                        ? existing.map(InvestmentThesis::symbol).orElse(symbol)
                        : symbol,
                summary == null || summary.isBlank()
                        ? existing.map(InvestmentThesis::summary).orElse(summary)
                        : summary,
                invalidation == null
                        ? existing.map(InvestmentThesis::invalidation).orElse("")
                        : invalidation,
                InvestmentThesisStatus.ACTIVE,
                nextReviewAt == null ? existing.map(InvestmentThesis::nextReviewAt).orElse(null) : nextReviewAt,
                existing.map(InvestmentThesis::createdAt).orElse(now),
                now,
                null);
        return store.saveThesis(thesis);
    }

    @Transactional
    public InvestmentThesis closeThesis(String ownerId, UUID thesisId) {
        String owner = InvestmentPolicy.requireOwner(ownerId);
        InvestmentThesis existing = store.findThesis(thesisId, owner)
                .orElseThrow(() -> new IllegalArgumentException("investment thesis was not found for this owner"));
        if (existing.status() == InvestmentThesisStatus.CLOSED) return existing;
        Instant now = clock.instant();
        return store.saveThesis(new InvestmentThesis(
                existing.id(), existing.ownerId(), existing.conversationId(), existing.symbol(), existing.summary(),
                existing.invalidation(), InvestmentThesisStatus.CLOSED, existing.nextReviewAt(),
                existing.createdAt(), now, now));
    }

    public List<InvestmentThesis> theses(String ownerId, InvestmentThesisStatus status) {
        return List.copyOf(store.listTheses(InvestmentPolicy.requireOwner(ownerId), status));
    }

    public Map<String, InvestmentQuotePriority> quotePriorities(String ownerId) {
        return Map.copyOf(store.listQuotePriorities(InvestmentPolicy.requireOwner(ownerId)));
    }

    @Transactional
    public Map<String, InvestmentQuotePriority> setQuotePriorities(
            String ownerId, List<String> symbols, InvestmentQuotePriority priority) {
        String owner = InvestmentPolicy.requireOwner(ownerId);
        if (priority == null) throw new IllegalArgumentException("quote priority must not be null");
        List<String> normalizedSymbols = symbols == null ? List.of() : symbols.stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (normalizedSymbols.isEmpty()) throw new IllegalArgumentException("at least one symbol is required");

        List<String> heldSymbols = summary(owner).positions().stream().map(PortfolioPosition::symbol).toList();
        List<String> unknownSymbols = normalizedSymbols.stream()
                .filter(symbol -> !heldSymbols.contains(symbol)).toList();
        if (!unknownSymbols.isEmpty()) {
            throw new IllegalArgumentException("quote priority can only be set for held symbols: "
                    + String.join(", ", unknownSymbols));
        }
        Instant now = clock.instant();
        normalizedSymbols.forEach(symbol -> store.saveQuotePriority(owner, symbol, priority, now));
        return quotePriorities(owner);
    }

    public PortfolioSummary summary(String ownerId) {
        String owner = InvestmentPolicy.requireOwner(ownerId);
        InvestmentPolicy policy = policy(owner);
        Map<String, MutablePosition> bySymbol = new LinkedHashMap<>();
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalFees = BigDecimal.ZERO;
        BigDecimal netCashContribution = BigDecimal.ZERO;

        List<InvestmentTransaction> transactions = new ArrayList<>(store.listTransactions(owner));
        transactions.removeIf(transaction -> !transaction.active());
        transactions.sort(Comparator.comparing(InvestmentTransaction::occurredAt)
                .thenComparing(InvestmentTransaction::createdAt)
                .thenComparing(InvestmentTransaction::id));
        for (InvestmentTransaction transaction : transactions) {
            MutablePosition position = transaction.symbol().isBlank() ? null
                    : bySymbol.computeIfAbsent(transaction.symbol(), ignored -> new MutablePosition(transaction));
            if (position != null) position.refreshMetadata(transaction);
            switch (transaction.type()) {
                case BUY -> {
                    BigDecimal cost = transaction.tradeValue().add(transaction.fee());
                    position.quantity = position.quantity.add(transaction.quantity());
                    position.costBasis = position.costBasis.add(cost);
                    position.fees = position.fees.add(transaction.fee());
                    totalFees = totalFees.add(transaction.fee());
                }
                case SELL -> {
                    if (position.quantity.compareTo(transaction.quantity()) < 0) {
                        throw new IllegalStateException("investment ledger contains an oversold position: "
                                + transaction.symbol());
                    }
                    BigDecimal averageCost = divide(position.costBasis, position.quantity);
                    BigDecimal removedCost = averageCost.multiply(transaction.quantity(), MATH);
                    BigDecimal proceeds = transaction.tradeValue().subtract(transaction.fee());
                    position.quantity = position.quantity.subtract(transaction.quantity());
                    position.costBasis = position.quantity.signum() == 0
                            ? BigDecimal.ZERO : position.costBasis.subtract(removedCost);
                    position.realized = position.realized.add(proceeds.subtract(removedCost));
                    position.fees = position.fees.add(transaction.fee());
                    totalFees = totalFees.add(transaction.fee());
                }
                case DIVIDEND -> {
                    position.income = position.income.add(transaction.amount());
                    totalIncome = totalIncome.add(transaction.amount());
                }
                case FEE -> {
                    totalFees = totalFees.add(transaction.amount());
                    if (position != null) {
                        position.fees = position.fees.add(transaction.amount());
                    }
                }
                case CASH_DEPOSIT -> netCashContribution = netCashContribution.add(transaction.amount());
                case CASH_WITHDRAWAL -> netCashContribution = netCashContribution.subtract(transaction.amount());
            }
        }

        BigDecimal totalCost = bySymbol.values().stream()
                .map(position -> position.costBasis)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> warnings = new ArrayList<>();
        List<PortfolioPosition> positions = bySymbol.values().stream()
                .filter(position -> position.quantity.signum() > 0)
                .sorted(Comparator.comparing(position -> position.symbol))
                .map(position -> toPosition(position, totalCost, policy, warnings))
                .toList();
        BigDecimal realized = bySymbol.values().stream()
                .map(position -> position.realized)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PortfolioSummary(
                owner, policy.baseCurrency(), "AVERAGE_COST",
                clean(totalCost), clean(realized), clean(totalIncome), clean(totalFees), clean(netCashContribution),
                positions, policy, List.copyOf(warnings));
    }

    public Map<String, Object> simulateBuy(String ownerId, String symbol, BigDecimal amount) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol must not be blank");
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("simulated buy amount must be greater than zero");
        }
        String normalizedSymbol = symbol.trim().toUpperCase(Locale.ROOT);
        PortfolioSummary current = summary(ownerId);
        BigDecimal currentSymbolCost = current.positions().stream()
                .filter(position -> position.symbol().equals(normalizedSymbol))
                .map(PortfolioPosition::costBasis)
                .findFirst().orElse(BigDecimal.ZERO);
        BigDecimal projectedSymbolCost = currentSymbolCost.add(amount);
        BigDecimal projectedTotalCost = current.totalOpenCostBasis().add(amount);
        BigDecimal allocation = percent(projectedSymbolCost, projectedTotalCost);
        boolean exceedsPolicy = allocation.compareTo(current.policy().maxSinglePositionPercent()) > 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("simulation_only", true);
        result.put("valuation_basis", "AVERAGE_COST; no live market price was used");
        result.put("symbol", normalizedSymbol);
        result.put("currency", current.baseCurrency());
        result.put("additional_cost", clean(amount));
        result.put("projected_symbol_cost", clean(projectedSymbolCost));
        result.put("projected_total_cost", clean(projectedTotalCost));
        result.put("projected_cost_allocation_percent", allocation);
        result.put("policy_limit_percent", current.policy().maxSinglePositionPercent());
        result.put("exceeds_policy", exceedsPolicy);
        return Map.copyOf(result);
    }

    private PortfolioPosition toPosition(
            MutablePosition position,
            BigDecimal totalCost,
            InvestmentPolicy policy,
            List<String> warnings) {
        BigDecimal allocation = percent(position.costBasis, totalCost);
        if (allocation.compareTo(policy.maxSinglePositionPercent()) > 0) {
            warnings.add(position.symbol + " cost allocation " + plain(allocation)
                    + "% exceeds the policy limit " + plain(policy.maxSinglePositionPercent()) + "%");
        }
        return new PortfolioPosition(
                position.symbol, position.instrumentName, position.assetClass, position.currency,
                clean(position.quantity), clean(position.costBasis), clean(divide(position.costBasis, position.quantity)),
                allocation, clean(position.realized), clean(position.income), clean(position.fees));
    }

    private InvestmentPolicy defaultPolicy(String ownerId) {
        Instant now = clock.instant();
        return new InvestmentPolicy(ownerId, defaultBaseCurrency, "", new BigDecimal("20"), now, now);
    }

    private void validateTradeSequence(List<InvestmentTransaction> transactions) {
        Map<String, BigDecimal> quantities = new LinkedHashMap<>();
        transactions.stream()
                .sorted(Comparator.comparing(InvestmentTransaction::occurredAt)
                        .thenComparing(InvestmentTransaction::createdAt)
                        .thenComparing(InvestmentTransaction::id))
                .filter(transaction -> transaction.type().securityTrade())
                .forEach(transaction -> {
                    BigDecimal current = quantities.getOrDefault(transaction.symbol(), BigDecimal.ZERO);
                    BigDecimal next = transaction.type() == InvestmentTransactionType.BUY
                            ? current.add(transaction.quantity()) : current.subtract(transaction.quantity());
                    if (next.signum() < 0) {
                        throw new IllegalArgumentException(
                                "voiding this transaction would leave a historical oversell for "
                                        + transaction.symbol());
                    }
                    quantities.put(transaction.symbol(), next);
                });
    }

    private BigDecimal percent(BigDecimal portion, BigDecimal total) {
        if (total.signum() == 0) return BigDecimal.ZERO;
        return clean(portion.multiply(HUNDRED).divide(total, 6, RoundingMode.HALF_UP));
    }

    private BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) return BigDecimal.ZERO;
        return numerator.divide(denominator, 12, RoundingMode.HALF_UP);
    }

    private BigDecimal clean(BigDecimal value) {
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private String plain(BigDecimal value) {
        return clean(value).toPlainString();
    }

    private static final class MutablePosition {
        private final String symbol;
        private String instrumentName;
        private String assetClass;
        private final String currency;
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal costBasis = BigDecimal.ZERO;
        private BigDecimal realized = BigDecimal.ZERO;
        private BigDecimal income = BigDecimal.ZERO;
        private BigDecimal fees = BigDecimal.ZERO;

        private MutablePosition(InvestmentTransaction transaction) {
            this.symbol = transaction.symbol();
            this.instrumentName = transaction.instrumentName();
            this.assetClass = transaction.assetClass();
            this.currency = transaction.currency();
        }

        private void refreshMetadata(InvestmentTransaction transaction) {
            if (!transaction.instrumentName().isBlank()) this.instrumentName = transaction.instrumentName();
            if (!transaction.assetClass().isBlank()) this.assetClass = transaction.assetClass();
        }
    }
}
