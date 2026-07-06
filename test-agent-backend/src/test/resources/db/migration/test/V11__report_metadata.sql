ALTER TABLE report
    ADD COLUMN metadata jsonb NOT NULL DEFAULT '{}';
