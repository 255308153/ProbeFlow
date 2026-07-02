CREATE TABLE knowledge_document (
    document_id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    system_name VARCHAR(128),
    module_name VARCHAR(128),
    doc_type VARCHAR(32) NOT NULL,
    biz_entity VARCHAR(128),
    source_type VARCHAR(32) NOT NULL,
    source_ref TEXT,
    authority VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    raw_content TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_knowledge_document_type_module
    ON knowledge_document (doc_type, system_name, module_name);

CREATE TABLE knowledge_document_revision (
    document_revision_id VARCHAR(36) PRIMARY KEY,
    document_id VARCHAR(36) NOT NULL,
    version INTEGER NOT NULL,
    latest BOOLEAN NOT NULL DEFAULT FALSE,
    revision_status VARCHAR(32) NOT NULL,
    source_hash VARCHAR(128),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_knowledge_revision_document_latest
    ON knowledge_document_revision (document_id, latest);

CREATE TABLE knowledge_chunk (
    chunk_id VARCHAR(36) PRIMARY KEY,
    document_id VARCHAR(36) NOT NULL,
    document_revision_id VARCHAR(36) NOT NULL,
    chunk_status VARCHAR(16) NOT NULL,
    chunk_title VARCHAR(255),
    chunk_content TEXT NOT NULL,
    chunk_order INTEGER NOT NULL,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    applicable_stages JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    token_count INTEGER NOT NULL DEFAULT 0,
    embedding vector(1024) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_knowledge_chunk_document_revision
    ON knowledge_chunk (document_id, document_revision_id, chunk_order);

CREATE INDEX idx_knowledge_chunk_status
    ON knowledge_chunk (chunk_status);

CREATE INDEX idx_knowledge_chunk_embedding
    ON knowledge_chunk USING hnsw (embedding vector_cosine_ops);
