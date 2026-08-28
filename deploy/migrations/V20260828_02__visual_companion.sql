CREATE TABLE IF NOT EXISTS minikun_inspiration_board (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    title VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_minikun_inspiration_board_owner
    ON minikun_inspiration_board (owner_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS minikun_inspiration_board_item (
    id UUID PRIMARY KEY,
    board_id UUID NOT NULL REFERENCES minikun_inspiration_board(id) ON DELETE CASCADE,
    image_url VARCHAR(4000) NOT NULL,
    source_url VARCHAR(4000) NOT NULL DEFAULT '',
    title VARCHAR(240) NOT NULL DEFAULT '',
    description VARCHAR(1000) NOT NULL DEFAULT '',
    origin VARCHAR(24) NOT NULL DEFAULT 'web',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_minikun_inspiration_board_item_board
    ON minikun_inspiration_board_item (board_id, created_at DESC);
