-- Manual, additive migration. No application startup DDL, no changes to public/ai/auth.
-- Execute with psql -X -v ON_ERROR_STOP=1 against the explicitly approved database.
BEGIN;
CREATE SCHEMA IF NOT EXISTS support;
CREATE TABLE IF NOT EXISTS support.conversations (
    id uuid PRIMARY KEY,
    visitor_key uuid NOT NULL UNIQUE,
    status varchar(6) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    closed_at timestamptz,
    last_message_at timestamptz,
    last_message varchar(2000),
    next_sequence bigint NOT NULL DEFAULT 0 CHECK (next_sequence >= 0),
    admin_read_sequence bigint NOT NULL DEFAULT 0 CHECK (admin_read_sequence >= 0)
);
CREATE TABLE IF NOT EXISTS support.messages (
    id uuid PRIMARY KEY,
    conversation_id uuid NOT NULL REFERENCES support.conversations(id),
    sequence bigint NOT NULL CHECK (sequence > 0),
    client_message_id uuid NOT NULL,
    sender_type varchar(7) NOT NULL CHECK (sender_type IN ('VISITOR', 'ADMIN')),
    message_text varchar(2000) NOT NULL CHECK (length(trim(message_text)) > 0),
    created_at timestamptz NOT NULL,
    UNIQUE (conversation_id, sequence),
    UNIQUE (conversation_id, sender_type, client_message_id)
);
CREATE INDEX IF NOT EXISTS support_conversations_recent ON support.conversations(updated_at DESC, id);
CREATE INDEX IF NOT EXISTS support_messages_sender_time ON support.messages(conversation_id, sender_type, created_at);
COMMIT;
