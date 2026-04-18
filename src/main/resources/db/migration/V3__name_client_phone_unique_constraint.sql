DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM pg_constraint c
          JOIN pg_class t ON t.oid = c.conrelid
          JOIN pg_namespace n ON n.oid = t.relnamespace
         WHERE c.conname = 'client_phone_key'
           AND t.relname = 'client'
           AND n.nspname = current_schema()
    ) THEN
        ALTER TABLE client RENAME CONSTRAINT client_phone_key TO uq_client_phone;
    END IF;
END
$$;
