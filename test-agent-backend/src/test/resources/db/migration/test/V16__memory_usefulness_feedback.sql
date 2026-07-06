CREATE TABLE memory_usefulness_feedback (
    feedback_id VARCHAR(36) PRIMARY KEY,
    usage_id VARCHAR(36) NOT NULL,
    memory_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    reason TEXT,
    rejection_reason TEXT,
    sanitized_summary TEXT NOT NULL,
    confidence_delta REAL NOT NULL DEFAULT 0,
    importance_delta REAL NOT NULL DEFAULT 0,
    success_contribution_delta REAL NOT NULL DEFAULT 0,
    previous_status VARCHAR(16) NOT NULL,
    new_status VARCHAR(16) NOT NULL,
    metadata JSON NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_memory_usefulness_usage_actor_outcome UNIQUE (usage_id, actor, outcome)
);

CREATE INDEX idx_memory_usefulness_memory_created
    ON memory_usefulness_feedback (memory_id, created_at);

CREATE INDEX idx_memory_usefulness_usage_created
    ON memory_usefulness_feedback (usage_id, created_at);

CREATE INDEX idx_memory_usefulness_status_outcome
    ON memory_usefulness_feedback (status, outcome);
