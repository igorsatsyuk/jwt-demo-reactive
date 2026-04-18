CREATE INDEX IF NOT EXISTS idx_request_pending_client_create_created_at
    ON request (created_at)
    WHERE status = 'PENDING' AND type = 'CLIENT_CREATE';
