CREATE TABLE IF NOT EXISTS minikun_investment_policy (
    owner_id VARCHAR(255) PRIMARY KEY,
    base_currency VARCHAR(3) NOT NULL,
    benchmark VARCHAR(64) NOT NULL DEFAULT '',
    max_single_position_percent NUMERIC(9, 6) NOT NULL DEFAULT 20,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (max_single_position_percent > 0 AND max_single_position_percent <= 100)
);

CREATE TABLE IF NOT EXISTS minikun_investment_transaction (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    account_name TEXT NOT NULL,
    transaction_type VARCHAR(32) NOT NULL,
    symbol VARCHAR(64) NOT NULL DEFAULT '',
    instrument_name TEXT NOT NULL DEFAULT '',
    asset_class VARCHAR(32) NOT NULL DEFAULT 'OTHER',
    currency VARCHAR(3) NOT NULL,
    quantity NUMERIC(38, 12),
    unit_price NUMERIC(38, 12),
    amount NUMERIC(38, 12),
    fee NUMERIC(38, 12) NOT NULL DEFAULT 0,
    occurred_at TIMESTAMPTZ NOT NULL,
    note TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL,
    voided_at TIMESTAMPTZ,
    CHECK (transaction_type IN ('BUY', 'SELL', 'DIVIDEND', 'FEE', 'CASH_DEPOSIT', 'CASH_WITHDRAWAL')),
    CHECK (fee >= 0),
    CHECK (
        (transaction_type IN ('BUY', 'SELL') AND symbol <> '' AND quantity > 0
            AND unit_price >= 0 AND amount IS NULL)
        OR
        (transaction_type NOT IN ('BUY', 'SELL') AND quantity IS NULL
            AND unit_price IS NULL AND amount > 0)
    ),
    CHECK (transaction_type <> 'DIVIDEND' OR symbol <> '')
);

CREATE INDEX IF NOT EXISTS idx_minikun_investment_transaction_owner_time
    ON minikun_investment_transaction (owner_id, occurred_at, created_at);

CREATE INDEX IF NOT EXISTS idx_minikun_investment_transaction_owner_symbol
    ON minikun_investment_transaction (owner_id, symbol, occurred_at);

CREATE TABLE IF NOT EXISTS minikun_investment_thesis (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    summary TEXT NOT NULL,
    invalidation TEXT NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    next_review_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    CHECK (status IN ('ACTIVE', 'CLOSED')),
    CHECK ((status = 'ACTIVE' AND closed_at IS NULL) OR (status = 'CLOSED' AND closed_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_minikun_investment_thesis_owner_status
    ON minikun_investment_thesis (owner_id, status, next_review_at);
