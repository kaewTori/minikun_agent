ALTER TABLE minikun_chat_conversation
    ADD COLUMN IF NOT EXISTS pinned BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE minikun_chat_conversation
    ADD COLUMN IF NOT EXISTS archived BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE minikun_chat_message
    ADD COLUMN IF NOT EXISTS metadata_json TEXT;

CREATE INDEX IF NOT EXISTS idx_minikun_chat_conversation_active
    ON minikun_chat_conversation(owner_id, archived, pinned DESC, updated_at DESC);
