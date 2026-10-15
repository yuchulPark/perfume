-- Manual migration only. This file is outside Spring's resources/init paths.
-- Run as perfume_user against perfume AFTER backup/review/approval. Never auto-run at startup.
-- Deliberately fails if ai already exists; investigate rather than overwrite existing AI data.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $$
BEGIN
    IF current_database() <> 'perfume' OR current_user <> 'perfume_user'
       OR current_setting('server_version_num') <> '170011' THEN
        RAISE EXCEPTION 'Unexpected embedding migration target';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
                   WHERE e.extname = 'vector' AND e.extversion = '0.8.7' AND n.nspname = 'public') THEN
        RAISE EXCEPTION 'Existing public pgvector 0.8.7 is required; no extension is created by this migration';
    END IF;
END $$;

CREATE SCHEMA ai AUTHORIZATION perfume_user;
REVOKE ALL ON SCHEMA ai FROM PUBLIC;

CREATE TABLE ai.perfume_embeddings (
    perfume_id bigint NOT NULL REFERENCES public.perfumes(id) ON DELETE RESTRICT,
    model_name varchar(128) NOT NULL CHECK (length(model_name) > 0),
    model_digest varchar(71) NOT NULL CHECK (model_digest ~ '^sha256:[0-9a-f]{64}$'),
    text_version varchar(32) NOT NULL CHECK (length(text_version) > 0),
    source_text text NOT NULL CHECK (length(source_text) > 0),
    source_sha256 varchar(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    embedding public.vector(1024) NOT NULL CHECK (public.vector_norm(embedding) > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_perfume_embeddings PRIMARY KEY (perfume_id, model_name, model_digest, text_version)
);
REVOKE ALL ON ai.perfume_embeddings FROM PUBLIC;

COMMENT ON TABLE ai.perfume_embeddings IS 'Manual incremental embeddings; public catalog rows are never modified by the batch';
COMMENT ON COLUMN ai.perfume_embeddings.model_digest IS 'Full installed Ollama manifest digest, not a mutable tag or image ID';
COMMENT ON COLUMN ai.perfume_embeddings.source_sha256 IS 'SHA-256 of UTF-8 source_text in the fixed text_version format';
COMMIT;
