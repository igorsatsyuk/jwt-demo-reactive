CREATE TABLE account (
                        id          BIGSERIAL PRIMARY KEY,
                        balance     NUMERIC(19, 2) NOT NULL DEFAULT 0,
                        client_id   BIGINT NOT NULL,
                        version     BIGINT NOT NULL DEFAULT 0,
                        CONSTRAINT fk_account_client FOREIGN KEY (client_id) REFERENCES client (id),
                        CONSTRAINT uq_account_client UNIQUE (client_id)
);

CREATE INDEX idx_account_client_id ON account (client_id);
