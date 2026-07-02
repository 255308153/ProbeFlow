CREATE DOMAIN IF NOT EXISTS vector AS VARCHAR(65535);

CREATE TABLE knowledge_document (
    document_id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    system_name VARCHAR(128),
    module_name VARCHAR(128),
    doc_type VARCHAR(32) NOT NULL,
    biz_entity VARCHAR(128),
    source_type VARCHAR(32) NOT NULL,
    source_ref VARCHAR(4096),
    authority VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}',
    raw_content VARCHAR(4096),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
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
    metadata jsonb NOT NULL DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_knowledge_revision_document_latest
    ON knowledge_document_revision (document_id, latest);

CREATE TABLE knowledge_chunk (
    chunk_id VARCHAR(36) PRIMARY KEY,
    document_id VARCHAR(36) NOT NULL,
    document_revision_id VARCHAR(36) NOT NULL,
    chunk_status VARCHAR(16) NOT NULL,
    chunk_title VARCHAR(255),
    chunk_content VARCHAR(4096) NOT NULL,
    chunk_order INTEGER NOT NULL,
    tags jsonb NOT NULL DEFAULT '[]',
    applicable_stages jsonb NOT NULL DEFAULT '[]',
    metadata jsonb NOT NULL DEFAULT '{}',
    token_count INTEGER NOT NULL DEFAULT 0,
    embedding vector NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_knowledge_chunk_document_revision
    ON knowledge_chunk (document_id, document_revision_id, chunk_order);

CREATE INDEX idx_knowledge_chunk_status
    ON knowledge_chunk (chunk_status);

CREATE INDEX idx_knowledge_chunk_embedding
    ON knowledge_chunk (embedding);
