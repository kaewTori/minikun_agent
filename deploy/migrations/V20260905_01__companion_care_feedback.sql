ALTER TABLE minikun_conversation_thread
    ADD COLUMN IF NOT EXISTS last_check_in_feedback VARCHAR(24);

ALTER TABLE minikun_conversation_thread
    ADD COLUMN IF NOT EXISTS check_in_count INTEGER NOT NULL DEFAULT 0;
