CREATE TABLE human_review_request (
    request_id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    source_step_id VARCHAR(36),
    request_type VARCHAR(48) NOT NULL,
    status VARCHAR(32) NOT NULL,
    waiting_reason TEXT NOT NULL,
    required_input_schema jsonb NOT NULL DEFAULT '[]',
    risk_level VARCHAR(16) NOT NULL,
    source_trigger VARCHAR(64) NOT NULL,
    planner_decision_id VARCHAR(128),
    policy_reason VARCHAR(128),
    metadata jsonb NOT NULL DEFAULT '{}',
    expires_at TIMESTAMP,
    answered_at TIMESTAMP,
    consumed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_human_review_request_task_created
    ON human_review_request (task_id, created_at);

CREATE INDEX idx_human_review_request_status_created
    ON human_review_request (status, created_at);

CREATE INDEX idx_human_review_request_type_created
    ON human_review_request (request_type, created_at);

CREATE TABLE human_decision_record (
    decision_id VARCHAR(36) PRIMARY KEY,
    request_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    decision_type VARCHAR(48) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    reason TEXT,
    payload jsonb NOT NULL DEFAULT '{}',
    sanitized_payload_summary jsonb NOT NULL DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_human_decision_record_task_created
    ON human_decision_record (task_id, created_at);

CREATE INDEX idx_human_decision_record_request_created
    ON human_decision_record (request_id, created_at);
