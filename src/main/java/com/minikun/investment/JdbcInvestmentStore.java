package com.minikun.investment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** PostgreSQL-backed owner-scoped investment ledger, policy, and thesis journal. */
@Component
@ConditionalOnProperty(name = "minikun.investment.enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcInvestmentStore implements InvestmentStore {
    private final JdbcTemplate jdbc;

    public JdbcInvestmentStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc template must not be null");
    }

    @Override
    public Optional<InvestmentPolicy> findPolicy(String ownerId) {
        return jdbc.query("""
                SELECT owner_id, base_currency, benchmark, max_single_position_percent, created_at, updated_at
                FROM minikun_investment_policy WHERE owner_id = ?
                """, this::mapPolicy, ownerId).stream().findFirst();
    }

    @Override
    public InvestmentPolicy savePolicy(InvestmentPolicy policy) {
        jdbc.update("""
                INSERT INTO minikun_investment_policy
                    (owner_id, base_currency, benchmark, max_single_position_percent, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (owner_id) DO UPDATE SET
                    base_currency = EXCLUDED.base_currency,
                    benchmark = EXCLUDED.benchmark,
                    max_single_position_percent = EXCLUDED.max_single_position_percent,
                    updated_at = EXCLUDED.updated_at
                """, policy.ownerId(), policy.baseCurrency(), policy.benchmark(),
                policy.maxSinglePositionPercent(), timestamp(policy.createdAt()), timestamp(policy.updatedAt()));
        return policy;
    }

    @Override
    public InvestmentTransaction addTransaction(InvestmentTransaction transaction) {
        jdbc.update("""
                INSERT INTO minikun_investment_transaction
                    (id, owner_id, conversation_id, account_name, transaction_type, symbol, instrument_name,
                     asset_class, currency, quantity, unit_price, amount, fee, occurred_at, note, created_at, voided_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, transaction.id(), transaction.ownerId(), transaction.conversationId(), transaction.account(),
                transaction.type().name(), transaction.symbol(), transaction.instrumentName(), transaction.assetClass(),
                transaction.currency(), transaction.quantity(), transaction.unitPrice(), transaction.amount(),
                transaction.fee(), timestamp(transaction.occurredAt()), transaction.note(),
                timestamp(transaction.createdAt()), timestamp(transaction.voidedAt()));
        return transaction;
    }

    @Override
    public List<InvestmentTransaction> listTransactions(String ownerId) {
        return jdbc.query("""
                SELECT id, owner_id, conversation_id, account_name, transaction_type, symbol, instrument_name,
                       asset_class, currency, quantity, unit_price, amount, fee, occurred_at, note, created_at, voided_at
                FROM minikun_investment_transaction
                WHERE owner_id = ?
                ORDER BY occurred_at, created_at, id
                """, this::mapTransaction, ownerId);
    }

    @Override
    public boolean voidTransaction(UUID id, String ownerId, Instant voidedAt) {
        return jdbc.update("""
                UPDATE minikun_investment_transaction SET voided_at = ?
                WHERE id = ? AND owner_id = ? AND voided_at IS NULL
                """, timestamp(voidedAt), id, ownerId) > 0;
    }

    @Override
    public Optional<InvestmentThesis> findThesis(UUID id, String ownerId) {
        return jdbc.query("""
                SELECT id, owner_id, conversation_id, symbol, summary, invalidation, status,
                       next_review_at, created_at, updated_at, closed_at
                FROM minikun_investment_thesis WHERE id = ? AND owner_id = ?
                """, this::mapThesis, id, ownerId).stream().findFirst();
    }

    @Override
    public InvestmentThesis saveThesis(InvestmentThesis thesis) {
        jdbc.update("""
                INSERT INTO minikun_investment_thesis
                    (id, owner_id, conversation_id, symbol, summary, invalidation, status,
                     next_review_at, created_at, updated_at, closed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    conversation_id = EXCLUDED.conversation_id,
                    symbol = EXCLUDED.symbol,
                    summary = EXCLUDED.summary,
                    invalidation = EXCLUDED.invalidation,
                    status = EXCLUDED.status,
                    next_review_at = EXCLUDED.next_review_at,
                    updated_at = EXCLUDED.updated_at,
                    closed_at = EXCLUDED.closed_at
                WHERE minikun_investment_thesis.owner_id = EXCLUDED.owner_id
                """, thesis.id(), thesis.ownerId(), thesis.conversationId(), thesis.symbol(), thesis.summary(),
                thesis.invalidation(), thesis.status().name(), timestamp(thesis.nextReviewAt()),
                timestamp(thesis.createdAt()), timestamp(thesis.updatedAt()), timestamp(thesis.closedAt()));
        return thesis;
    }

    @Override
    public List<InvestmentThesis> listTheses(String ownerId, InvestmentThesisStatus status) {
        if (status == null) {
            return jdbc.query("""
                    SELECT id, owner_id, conversation_id, symbol, summary, invalidation, status,
                           next_review_at, created_at, updated_at, closed_at
                    FROM minikun_investment_thesis
                    WHERE owner_id = ? ORDER BY status, next_review_at NULLS LAST, updated_at DESC
                    """, this::mapThesis, ownerId);
        }
        return jdbc.query("""
                SELECT id, owner_id, conversation_id, symbol, summary, invalidation, status,
                       next_review_at, created_at, updated_at, closed_at
                FROM minikun_investment_thesis
                WHERE owner_id = ? AND status = ?
                ORDER BY next_review_at NULLS LAST, updated_at DESC
                """, this::mapThesis, ownerId, status.name());
    }

    private InvestmentPolicy mapPolicy(ResultSet rs, int row) throws SQLException {
        return new InvestmentPolicy(
                rs.getString("owner_id"), rs.getString("base_currency"), rs.getString("benchmark"),
                rs.getBigDecimal("max_single_position_percent"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private InvestmentTransaction mapTransaction(ResultSet rs, int row) throws SQLException {
        return new InvestmentTransaction(
                uuid(rs, "id"), rs.getString("owner_id"), rs.getString("conversation_id"),
                rs.getString("account_name"), InvestmentTransactionType.valueOf(rs.getString("transaction_type")),
                rs.getString("symbol"), rs.getString("instrument_name"), rs.getString("asset_class"),
                rs.getString("currency"), rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("fee"), instant(rs, "occurred_at"),
                rs.getString("note"), instant(rs, "created_at"), instant(rs, "voided_at"));
    }

    private InvestmentThesis mapThesis(ResultSet rs, int row) throws SQLException {
        return new InvestmentThesis(
                uuid(rs, "id"), rs.getString("owner_id"), rs.getString("conversation_id"),
                rs.getString("symbol"), rs.getString("summary"), rs.getString("invalidation"),
                InvestmentThesisStatus.valueOf(rs.getString("status")), instant(rs, "next_review_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "closed_at"));
    }

    private UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
