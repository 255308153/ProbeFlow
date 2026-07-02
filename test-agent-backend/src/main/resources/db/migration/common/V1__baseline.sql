CREATE TABLE schema_marker (
    marker_key VARCHAR(64) PRIMARY KEY,
    marker_value VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO schema_marker (marker_key, marker_value)
VALUES ('phase', 'phase-1-backend-scaffold');
