ALTER TABLE request ADD COLUMN auth_client_id VARCHAR(255) NOT NULL DEFAULT 'unknown';

ALTER TABLE request DROP CONSTRAINT IF EXISTS request_pkey;
ALTER TABLE request ADD CONSTRAINT request_pkey PRIMARY KEY (id, auth_client_id);
