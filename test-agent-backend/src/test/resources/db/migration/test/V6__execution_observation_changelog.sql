CREATE TABLE execution_record (
    execution_id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    case_id VARCHAR(36) NOT NULL,
    step_id VARCHAR(36),
    executor_type VARCHAR(16) NOT NULL,
    environment VARCHAR(64),
    request_snapshot jsonb NOT NULL DEFAULT '{}',
    response_snapshot jsonb NOT NULL DEFAULT '{}',
    assertion_results jsonb NOT NULL DEFAULT '[]',
    overall_status VARCHAR(32) NOT NULL,
    critical_failed BOOLEAN NOT NULL DEFAULT FALSE,
    duration_ms BIGINT,
    status_code INTEGER,
    error_message VARCHAR(4096),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_execution_record_task_case_time
    ON execution_record (task_id, case_id, created_at);

CREATE INDEX idx_execution_record_case_created_at
    ON execution_record (case_id, created_at);

CREATE TABLE observation (
    observation_id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    execution_id VARCHAR(36) NOT NULL,
    observation_type VARCHAR(48) NOT NULL,
    analysis_level VARCHAR(16) NOT NULL,
    summary VARCHAR(4096),
    failure_reason VARCHAR(4096),
    risk_level VARCHAR(16) NOT NULL,
    next_suggestion VARCHAR(4096),
    source VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_observation_task_execution
    ON observation (task_id, execution_id);

CREATE TABLE change_log (
    change_id VARCHAR(36) PRIMARY KEY,
    entity_type VARCHAR(32) NOT NULL,
    entity_id VARCHAR(36) NOT NULL,
    before_snapshot jsonb NOT NULL DEFAULT '{}',
    after_snapshot jsonb NOT NULL DEFAULT '{}',
    change_type VARCHAR(32) NOT NULL,
    changed_by VARCHAR(128) NOT NULL,
    task_id VARCHAR(36),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_change_log_entity
    ON change_log (entity_type, entity_id, created_at);

CREATE INDEX idx_change_log_task
    ON change_log (task_id, created_at);
