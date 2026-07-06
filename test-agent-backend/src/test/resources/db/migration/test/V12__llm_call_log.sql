CREATE TABLE llm_call_log (
    llm_call_id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36),
    plan_step_id VARCHAR(36),
    purpose VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    template_id VARCHAR(128),
    template_version VARCHAR(32),
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    error_type VARCHAR(48),
    error_message VARCHAR(4096),
    latency_ms BIGINT,
    prompt_tokens INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    total_tokens INTEGER NOT NULL DEFAULT 0,
    prompt_summary VARCHAR(4096),
    response_summary VARCHAR(4096),
    provider_trace_id VARCHAR(128),
    fake_provider BOOLEAN NOT NULL DEFAULT FALSE,
    metadata jsonb NOT NULL DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_llm_call_log_task_created_at
    ON llm_call_log (task_id, created_at);

CREATE INDEX idx_llm_call_log_status_created_at
    ON llm_call_log (status, created_at);

CREATE INDEX idx_llm_call_log_purpose_created_at
    ON llm_call_log (purpose, created_at);
