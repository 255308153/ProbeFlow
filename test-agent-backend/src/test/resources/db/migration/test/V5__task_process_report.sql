CREATE TABLE task (
    task_id VARCHAR(36) PRIMARY KEY,
    task_type VARCHAR(32) NOT NULL,
    task_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_ref VARCHAR(4096),
    target_api_spec_ids jsonb NOT NULL DEFAULT '[]',
    promotion_mode VARCHAR(16) NOT NULL,
    memory_refinement_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED',
    memory_refined_at TIMESTAMP,
    priority VARCHAR(16) NOT NULL,
    creator VARCHAR(128),
    metadata jsonb NOT NULL DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_task_status_created_at
    ON task (status, created_at);

CREATE TABLE plan_step (
    step_id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    step_type VARCHAR(32) NOT NULL,
    step_status VARCHAR(16) NOT NULL,
    step_order INTEGER NOT NULL,
    goal VARCHAR(4096),
    input_ref VARCHAR(4096),
    retry_count INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE INDEX idx_plan_step_task_order
    ON plan_step (task_id, step_order);

CREATE TABLE task_case_execution (
    id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    case_id VARCHAR(36) NOT NULL,
    execution_mode VARCHAR(16) NOT NULL,
    snapshot_json jsonb NOT NULL DEFAULT '{}',
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
    summary VARCHAR(4096),
    case_count INTEGER NOT NULL DEFAULT 0,
    pass_count INTEGER NOT NULL DEFAULT 0,
    fail_count INTEGER NOT NULL DEFAULT 0,
    warning_count INTEGER NOT NULL DEFAULT 0,
    risk_summary VARCHAR(4096),
    findings jsonb NOT NULL DEFAULT '[]',
    suggestions jsonb NOT NULL DEFAULT '[]',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_report_task
    ON report (task_id);
