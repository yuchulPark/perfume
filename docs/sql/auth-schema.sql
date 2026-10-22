-- Operator-reviewed, manual migration only. Never run through application SQL init.
-- No catalog/public/ai table changes and no account/password seeds.
BEGIN;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE TABLE IF NOT EXISTS auth.app_users (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username varchar(64) NOT NULL UNIQUE CHECK (username ~ '^[a-zA-Z0-9_.-]{3,64}$'),
    password_hash varchar(60) NOT NULL CHECK (password_hash ~ '^\$2[aby]\$1[0-6]\$[./A-Za-z0-9]{53}$'),
    role varchar(16) NOT NULL CHECK (role IN ('ADMIN', 'USER')),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- Even concurrent bootstrap transactions can create only one ADMIN, including disabled accounts.
CREATE UNIQUE INDEX IF NOT EXISTS app_users_single_admin ON auth.app_users(role) WHERE role = 'ADMIN';
COMMENT ON TABLE auth.app_users IS 'Initial administrator only; no public registration. Managed outside Hibernate.';
COMMIT;
