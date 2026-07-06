CREATE TABLE memory_candidate_record (
    candidate_id VARCHAR(36) PRIMARY KEY,
    source_type VARCHAR(48) NOT NULL,
    source_ref VARCHAR(256) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    status VARCHAR(24) NOT NULL,
    summary VARCHAR(512) NOT NULL,
    content TEXT NOT NULL,
    sanitized_evidence TEXT,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    confidence REAL NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    idempotency_key VARCHAR(512) NOT NULL,
    rejection_reason TEXT,
    refinery_result_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    memory_id VARCHAR(36),
    audit_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_memory_candidate_source_task UNIQUE (source_type, source_ref, task_id)
);

CREATE INDEX idx_memory_candidate_task_created
    ON memory_candidate_record (task_id, created_at);

CREATE INDEX idx_memory_candidate_status_created
    ON memory_candidate_record (status, created_at);

CREATE INDEX idx_memory_candidate_memory
    ON memory_candidate_record (memory_id);
