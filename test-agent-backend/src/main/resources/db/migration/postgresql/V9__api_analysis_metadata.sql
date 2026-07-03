ALTER TABLE api_spec
    ADD COLUMN source_material_id VARCHAR(36);

ALTER TABLE api_spec
    ADD COLUMN operation_id VARCHAR(255);

ALTER TABLE api_spec
    ADD COLUMN description TEXT;

ALTER TABLE api_spec
    ADD COLUMN source_location JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX idx_api_spec_source_material_route
    ON api_spec (source_material_id, http_method, path);
