ALTER TABLE request ADD COLUMN idempotency_key UUID NULL;

CREATE UNIQUE INDEX idx_request_idempotency_key ON request (idempotency_key) WHERE idempotency_key IS NOT NULL;
