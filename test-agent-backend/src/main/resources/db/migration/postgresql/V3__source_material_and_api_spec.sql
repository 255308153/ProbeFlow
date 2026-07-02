CREATE TABLE source_material (
    material_id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36),
    material_type VARCHAR(32) NOT NULL,
    original_name VARCHAR(255),
    original_ref TEXT,
    storage_path TEXT,
    ingest_status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_source_material_task_id
    ON source_material (task_id);

CREATE INDEX idx_source_material_type_status
    ON source_material (material_type, ingest_status);

CREATE TABLE api_spec (
    api_spec_id VARCHAR(36) PRIMARY KEY,
    system_name VARCHAR(128) NOT NULL,
    module_name VARCHAR(128) NOT NULL,
    http_method VARCHAR(16) NOT NULL,
    path VARCHAR(1024) NOT NULL,
    summary TEXT,
    parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    constraints_doc JSONB NOT NULL DEFAULT '{}'::jsonb,
    auth JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_type VARCHAR(32) NOT NULL,
    source_ref TEXT,
    version INTEGER NOT NULL DEFAULT 1,
    route_ready BOOLEAN NOT NULL DEFAULT FALSE,
    basic_param_ready BOOLEAN NOT NULL DEFAULT FALSE,
    dto_expanded BOOLEAN NOT NULL DEFAULT FALSE,
    validation_ready BOOLEAN NOT NULL DEFAULT FALSE,
    auth_ready BOOLEAN NOT NULL DEFAULT FALSE,
    knowledge_context_ready BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_api_spec_module_path
    ON api_spec (module_name, path);

CREATE INDEX idx_api_spec_readiness
    ON api_spec (route_ready, basic_param_ready, dto_expanded, validation_ready, auth_ready, knowledge_context_ready);
