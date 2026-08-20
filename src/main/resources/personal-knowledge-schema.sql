CREATE TABLE IF NOT EXISTS minikun_knowledge_source (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    root_name VARCHAR(64) NOT NULL,
    relative_path TEXT NOT NULL,
    display_name TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    file_hash VARCHAR(64) NOT NULL DEFAULT '',
    modified_at TIMESTAMP WITH TIME ZONE,
    indexed_at TIMESTAMP WITH TIME ZONE,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT NOT NULL DEFAULT '',
    UNIQUE (owner_id, root_name, relative_path)
);

CREATE INDEX IF NOT EXISTS idx_minikun_knowledge_source_owner
    ON minikun_knowledge_source (owner_id, indexed_at DESC, id);

CREATE TABLE IF NOT EXISTS minikun_knowledge_chunk (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES minikun_knowledge_source(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    heading TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    embedding TEXT NOT NULL DEFAULT '',
    embedding_model VARCHAR(255) NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (source_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_minikun_knowledge_chunk_source
    ON minikun_knowledge_chunk (source_id, chunk_index);
