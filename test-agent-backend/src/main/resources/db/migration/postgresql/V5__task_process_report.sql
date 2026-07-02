CREATE TABLE task (
    task_id VARCHAR(36) PRIMARY KEY,
    task_type VARCHAR(32) NOT NULL,
    task_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_ref TEXT,
    target_api_spec_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    promotion_mode VARCHAR(16) NOT NULL,
    memory_refinement_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED',
    memory_refined_at TIMESTAMPTZ,
    priority VARCHAR(16) NOT NULL,
    creator VARCHAR(128),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_task_status_created_at
    ON task (status, created_at);

CREATE TABLE plan_step (
    step_id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    step_type VARCHAR(32) NOT NULL,
    step_status VARCHAR(16) NOT NULL,
    step_order INTEGER NOT NULL,
    goal TEXT,
    input_ref TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE INDEX idx_plan_step_task_order
    ON plan_step (task_id, step_order);

CREATE TABLE task_case_execution (
    id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    case_id VARCHAR(36) NOT NULL,
    execution_mode VARCHAR(16) NOT NULL,
    snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    execution_status VARCHAR(16) NOT NULL,
    execution_record_id VARCHAR(36)
);

CREATE INDEX idx_task_case_execution_task
    ON task_case_execution (task_id);

CREATE INDEX idx_task_case_execution_case
    ON task_case_execution (case_id);

CREATE TABLE report (
    report_id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    summary TEXT,
    case_count INTEGER NOT NULL DEFAULT 0,
    pass_count INTEGER NOT NULL DEFAULT 0,
    fail_count INTEGER NOT NULL DEFAULT 0,
    warning_count INTEGER NOT NULL DEFAULT 0,
    risk_summary TEXT,
    findings JSONB NOT NULL DEFAULT '[]'::jsonb,
    suggestions JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_report_task
    ON report (task_id);
