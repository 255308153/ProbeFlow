CREATE TABLE memory_usage_record (
    usage_id VARCHAR(36) PRIMARY KEY,
    memory_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    stage_profile VARCHAR(64) NOT NULL,
    consumer VARCHAR(48) NOT NULL,
    source_ref TEXT NOT NULL,
    citation_type VARCHAR(48) NOT NULL,
    citation_source_id VARCHAR(128) NOT NULL,
    citation_source_ref TEXT,
    score DOUBLE PRECISION NOT NULL,
    confidence REAL,
    low_confidence BOOLEAN NOT NULL DEFAULT FALSE,
    match_reasons JSON NOT NULL DEFAULT '[]',
    metadata JSON NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_memory_usage_task_created
    ON memory_usage_record (task_id, created_at);

CREATE INDEX idx_memory_usage_memory_created
    ON memory_usage_record (memory_id, created_at);

CREATE INDEX idx_memory_usage_consumer_stage
    ON memory_usage_record (consumer, stage_profile);
