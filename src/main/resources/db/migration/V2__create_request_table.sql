CREATE TABLE request (
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

CREATE INDEX idx_request_type ON request (type);
CREATE INDEX idx_request_status ON request (status);
CREATE INDEX idx_request_created_at ON request (created_at);

