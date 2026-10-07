ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS content_hash  varchar(64),
    ADD COLUMN IF NOT EXISTS status        varchar(20),
    ADD COLUMN IF NOT EXISTS error_message varchar(1000),
    ADD COLUMN IF NOT EXISTS finished_at   timestamp(6);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'documents' AND column_name = 'processed_at') THEN
        UPDATE documents SET finished_at = processed_at WHERE finished_at IS NULL;
        ALTER TABLE documents DROP COLUMN processed_at;
    END IF;
END $$;

ALTER TABLE documents DROP CONSTRAINT IF EXISTS documents_status_check;

DO $$
DECLARE
    constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT c.conname
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
        WHERE c.conrelid = 'documents'::regclass AND c.contype = 'u' AND a.attname = 'content_hash'
    LOOP
        EXECUTE format('ALTER TABLE documents DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;

UPDATE documents SET status = 'READY' WHERE status IS NULL;

UPDATE documents d
SET content_hash = h.content_hash
FROM (
    SELECT id,
           encode(sha256(data), 'hex') AS content_hash,
           row_number() OVER (PARTITION BY sha256(data) ORDER BY uploaded_at) AS position
    FROM documents
    WHERE data IS NOT NULL
) h
WHERE d.id = h.id
  AND h.position = 1
  AND d.content_hash IS NULL
  AND NOT EXISTS (SELECT 1 FROM documents x WHERE x.content_hash = h.content_hash);

ALTER TABLE documents
    ALTER COLUMN status SET NOT NULL,
    ADD CONSTRAINT documents_content_hash_key UNIQUE (content_hash);
