CREATE TABLE test_case (
    case_id VARCHAR(36) PRIMARY KEY,
    primary_api_spec_id VARCHAR(36) NOT NULL,
    case_category VARCHAR(32) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    preconditions JSONB NOT NULL DEFAULT '[]'::jsonb,
    expected_result TEXT,
    priority VARCHAR(16) NOT NULL,
    risk_level VARCHAR(16) NOT NULL,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    scenario_name VARCHAR(255),
    module_name VARCHAR(128),
    status VARCHAR(16) NOT NULL,
    source VARCHAR(32) NOT NULL,
    manual_edited BOOLEAN NOT NULL DEFAULT FALSE,
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    detail_type VARCHAR(32) NOT NULL,
    detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    steps JSONB NOT NULL DEFAULT '[]'::jsonb,
    stale_status VARCHAR(16) NOT NULL DEFAULT 'FRESH',
    based_on_api_spec_versions JSONB NOT NULL DEFAULT '{}'::jsonb,
    generated_from_single_case_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    generated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(128)
);

CREATE INDEX idx_test_case_primary_api_spec
    ON test_case (primary_api_spec_id);

CREATE INDEX idx_test_case_stale_status
    ON test_case (stale_status);

CREATE INDEX idx_test_case_module_scenario
    ON test_case (module_name, scenario_name);

CREATE TABLE test_case_draft (
    draft_id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    source VARCHAR(32) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    promotion_mode VARCHAR(16) NOT NULL,
    target_api_spec_id VARCHAR(36) NOT NULL,
    dedup_key VARCHAR(512) NOT NULL,
    expected_status_code INTEGER,
    draft_content JSONB NOT NULL DEFAULT '{}'::jsonb,
    promoted_case_id VARCHAR(36),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_test_case_draft_task_dedup
    ON test_case_draft (task_id, dedup_key);

CREATE INDEX idx_test_case_draft_target_status
    ON test_case_draft (target_api_spec_id, status);
