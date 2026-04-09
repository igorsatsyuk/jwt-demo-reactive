CREATE TABLE IF NOT EXISTS client (
                        id          BIGSERIAL PRIMARY KEY,
                        first_name  VARCHAR(100) NOT NULL,
                        last_name   VARCHAR(100) NOT NULL,
                        phone       VARCHAR(50) NOT NULL UNIQUE
);

DO $$
    BEGIN
        IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
            CREATE INDEX IF NOT EXISTS idx_client_first_name_trgm
                ON client
                    USING gin (lower(first_name) gin_trgm_ops);

            CREATE INDEX IF NOT EXISTS idx_client_last_name_trgm
                ON client
                    USING gin (lower(last_name) gin_trgm_ops);
        END IF;
    END
$$;

CREATE TABLE IF NOT EXISTS request (
                         id UUID PRIMARY KEY,
                         type VARCHAR(50) NOT NULL,
                         status VARCHAR(50) NOT NULL,
                         created_at TIMESTAMPTZ NOT NULL,
                         status_changed_at TIMESTAMPTZ NOT NULL,
                         request_data TEXT NOT NULL,
                         response_data TEXT NULL,
                         CONSTRAINT chk_request_type CHECK (type IN ('CLIENT_CREATE', 'OTHER')),
                         CONSTRAINT chk_request_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_request_type ON request (type);
CREATE INDEX IF NOT EXISTS idx_request_status ON request (status);
CREATE INDEX IF NOT EXISTS idx_request_created_at ON request (created_at);

CREATE TABLE IF NOT EXISTS account (
                         id          BIGSERIAL PRIMARY KEY,
                         balance     NUMERIC(19, 2) NOT NULL DEFAULT 0,
                         client_id   BIGINT NOT NULL,
                         version     BIGINT NOT NULL DEFAULT 0,
                         CONSTRAINT fk_account_client FOREIGN KEY (client_id) REFERENCES client (id),
                         CONSTRAINT uq_account_client UNIQUE (client_id)
);

CREATE INDEX IF NOT EXISTS idx_account_client_id ON account (client_id);
