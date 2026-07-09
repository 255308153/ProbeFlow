CREATE TABLE memory_graph_node (
    node_id VARCHAR(96) PRIMARY KEY,
    entity_type VARCHAR(48) NOT NULL,
    normalized_value VARCHAR(512) NOT NULL,
    display_value VARCHAR(512) NOT NULL,
    scope VARCHAR(512),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_memory_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    fact_fingerprints JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_summaries JSONB NOT NULL DEFAULT '[]'::jsonb,
    occurrence_count INTEGER NOT NULL DEFAULT 0,
    confidence DOUBLE PRECISION NOT NULL DEFAULT 0,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_memory_graph_node_identity
    ON memory_graph_node (entity_type, normalized_value, scope);

CREATE INDEX idx_memory_graph_node_type_value
    ON memory_graph_node (entity_type, normalized_value);

CREATE TABLE memory_graph_edge (
    edge_id VARCHAR(96) PRIMARY KEY,
    source_node_id VARCHAR(96) NOT NULL,
    target_node_id VARCHAR(96) NOT NULL,
    relation_type VARCHAR(64) NOT NULL,
    source_memory_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    fact_fingerprints JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_summaries JSONB NOT NULL DEFAULT '[]'::jsonb,
    occurrence_count INTEGER NOT NULL DEFAULT 0,
    confidence DOUBLE PRECISION NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_memory_graph_edge_source
        FOREIGN KEY (source_node_id) REFERENCES memory_graph_node (node_id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_graph_edge_target
        FOREIGN KEY (target_node_id) REFERENCES memory_graph_node (node_id) ON DELETE CASCADE
);

CREATE INDEX idx_memory_graph_edge_source
    ON memory_graph_edge (source_node_id, relation_type);

CREATE INDEX idx_memory_graph_edge_target
    ON memory_graph_edge (target_node_id, relation_type);
