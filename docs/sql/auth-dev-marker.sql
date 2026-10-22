-- Only mounted into compose.auth-dev.yaml. Never apply this marker to another database.
CREATE TABLE auth.development_only (id integer PRIMARY KEY CHECK (id = 1), isolated boolean NOT NULL CHECK (isolated));
INSERT INTO auth.development_only VALUES (1, true);
