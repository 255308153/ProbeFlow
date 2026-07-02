CREATE DOMAIN IF NOT EXISTS jsonb AS JSON;

CREATE TABLE source_material (
    material_id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36),
    material_type VARCHAR(32) NOT NULL,
    original_name VARCHAR(255),
    original_ref VARCHAR(4096),
    storage_path VARCHAR(4096),
    ingest_status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
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
    summary VARCHAR(4096),
    parameters jsonb NOT NULL DEFAULT '{}',
    constraints_doc jsonb NOT NULL DEFAULT '{}',
    auth jsonb NOT NULL DEFAULT '{}',
    source_type VARCHAR(32) NOT NULL,
    source_ref VARCHAR(4096),
    version INTEGER NOT NULL DEFAULT 1,
    route_ready BOOLEAN NOT NULL DEFAULT FALSE,
    basic_param_ready BOOLEAN NOT NULL DEFAULT FALSE,
    dto_expanded BOOLEAN NOT NULL DEFAULT FALSE,
    validation_ready BOOLEAN NOT NULL DEFAULT FALSE,
    auth_ready BOOLEAN NOT NULL DEFAULT FALSE,
    knowledge_context_ready BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_api_spec_module_path
    ON api_spec (module_name, path);

CREATE INDEX idx_api_spec_readiness
    ON api_spec (route_ready, basic_param_ready, dto_expanded, validation_ready, auth_ready, knowledge_context_ready);
