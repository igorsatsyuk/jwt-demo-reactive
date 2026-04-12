CREATE INDEX IF NOT EXISTS idx_request_status_type_status_changed_at
    ON request (status, type, status_changed_at);

