ALTER TABLE report
    ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
