CREATE TABLE task_memory_item (
    memory_id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    memory_type VARCHAR(16) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    summary VARCHAR(512) NOT NULL,
    content TEXT NOT NULL,
    tags JSON NOT NULL DEFAULT '[]',
    source_type VARCHAR(32) NOT NULL,
    source_ref TEXT,
    confidence REAL NOT NULL,
    status VARCHAR(16) NOT NULL,
    lifecycle_stage VARCHAR(32),
    metadata JSON NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_task_memory_task_status
    ON task_memory_item (task_id, status, created_at);

CREATE INDEX idx_task_memory_scope_type
    ON task_memory_item (scope_type);

CREATE TABLE long_term_memory (
    memory_id VARCHAR(36) PRIMARY KEY,
    memory_type VARCHAR(16) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    summary VARCHAR(512) NOT NULL,
    content TEXT NOT NULL,
    full_content TEXT,
    tags JSON NOT NULL DEFAULT '[]',
    source_type VARCHAR(32) NOT NULL,
    source_ref TEXT,
    confidence REAL NOT NULL,
    importance REAL NOT NULL,
    hit_count INTEGER NOT NULL DEFAULT 0,
    success_contribution REAL NOT NULL DEFAULT 0.3,
    status VARCHAR(16) NOT NULL,
    metadata JSON NOT NULL DEFAULT '{}',
    embedding vector NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP WITH TIME ZONE,
    archived_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_long_term_memory_scope_status
    ON long_term_memory (scope_type, status, importance);

CREATE INDEX idx_long_term_memory_embedding
    ON long_term_memory (embedding);
