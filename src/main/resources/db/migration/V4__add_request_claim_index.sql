CREATE INDEX IF NOT EXISTS idx_request_status_type_created_at
    ON request (status, type, created_at);
