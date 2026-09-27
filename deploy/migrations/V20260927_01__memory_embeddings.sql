CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE minikun_memory ADD COLUMN IF NOT EXISTS embedding vector;
ALTER TABLE minikun_memory ADD COLUMN IF NOT EXISTS embedding_model varchar(255);
