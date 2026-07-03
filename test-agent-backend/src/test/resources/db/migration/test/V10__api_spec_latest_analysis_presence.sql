ALTER TABLE api_spec
    ADD COLUMN present_in_latest_analysis BOOLEAN NOT NULL DEFAULT TRUE;
